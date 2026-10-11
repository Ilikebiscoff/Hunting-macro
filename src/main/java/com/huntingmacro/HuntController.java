package com.huntingmacro;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.huntingmacro.pathing.Planner;
import com.huntingmacro.pathing.WalkCache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hunting loop for the Diamond Reserve miner zombies:
 * <ol>
 *   <li>HUNT: pathfind (background {@link Planner}) to the nearest untouched zombie</li>
 *   <li>HIT: aim at it and hit it exactly once with the prime hunt axe</li>
 *   <li>GOLDEN: swap to the golden axe and use it (right click / hit / off, configurable)</li>
 *   <li>HOLE: look at the ground, place the black hole, swap back to the prime axe</li>
 *   <li>back to HUNT: zombies standing inside a fresh black hole are left to it, the next one is pathed to</li>
 * </ol>
 */
public class HuntController {
    private static final double ARRIVE_DIST = 1.2;
    private static final double LOOKAHEAD = 3.0;
    private static final float WALK_TURN_SPEED = 240f;
    private static final long AVOID_MS = 30_000;
    private static final long BLACKLIST_MS = 15_000;
    private static final long TAG_MS = 120_000;
    /** Eye to hitbox centre distance at which we stop walking and swing. */
    private static final double HIT_DIST = 2.9;
    private static final long HIT_TIMEOUT_MS = 2500;
    /** The walk grid is 96 wide: zombies further out than this can be seen (ESP) but not pathed to yet. */
    private static final double PLAN_RADIUS = 44.0;
    private static final long STUCK_MS = 8000;
    private static final double STUCK_RADIUS = 3.0;

    private enum Phase { HUNT, HIT, GOLDEN, RETURN, RESCUE }

    public enum Golden { USE, HIT, OFF }

    /** A list of points being walked, with progress. */
    private static final class Cursor {
        final List<Vec3> pts;
        final List<Planner.Move> moves; // null for the patrol route
        final boolean loop;
        int idx;

        Cursor(List<Vec3> pts, boolean loop) {
            this(pts, null, loop);
        }

        Cursor(List<Vec3> pts, List<Planner.Move> moves, boolean loop) {
            this.pts = pts;
            this.moves = moves;
            this.loop = loop;
        }

        Planner.Move moveAt(int i) {
            return moves == null || i < 0 || i >= moves.size() ? null : moves.get(i);
        }
    }

    private enum Follow { MOVING, ARRIVED, STUCK }

    // ---- settings (/hunt ...) ---------------------------------------------------------------
    /** Optional name-tag filter; empty = every zombie that gets ESP is hunted. */
    public volatile String mobFilter = "";
    public volatile String axeName = "Prime Huntaxe";
    public volatile String holeName = "Medium Pocket Black Hole";
    public volatile Golden golden = Golden.OFF;
    public volatile double range = 80.0;
    /** After this many kills the grey (already hit) zombies become targets again; 0 = never. */
    public volatile int refreshEvery = 5;
    /** More than this many lava incidents -> warp out and take the lift operator back to the hunting level. */
    public volatile boolean lavaRescue = true;
    public volatile int lavaLimit = 3;
    public volatile String rescueCmd = "warp deep";
    public volatile int liftX = 45, liftY = 150, liftZ = 15;
    public volatile String liftNpc = "Lift Operator";
    /** Sidebar text meaning "already in Deep Caverns": no lift needed, just hunt (empty = never skip). */
    public volatile String deepText = "Deep Caverns";
    /** How long one black hole needs to fully suck an enemy in (s), and how long before that we hit the next one. */
    public volatile double holeTimeSec = 5.0;
    public volatile double holeLeadSec = 0.5;
    /** Measure how long a black hole really takes and adapt {@link #holeTimeSec} (saved between sessions). */
    public volatile boolean holeAuto = true;
    public volatile int holeSamples;
    /** Timings learned after every hit/hole (seconds, 0 = not learned yet). */
    public volatile double useLatencySec, cooldownSec, hitToHoleSec;
    public volatile int timingSamples;
    /** Set when the lead was typed in by hand: auto-learning then leaves it alone. */
    public volatile boolean holeLeadManual;
    /** Fixed scan box (block coordinates, inclusive). Zombies outside it are ignored (no ESP, no hunting). */
    public volatile boolean areaOn = true;
    public volatile int areaX1 = -44, areaX2 = 28, areaZ1 = -27, areaZ2 = 56, areaY1 = 36, areaY2 = 40;
    /** Sidebar text of the hunting area; leaving it sends us back to where we were 3 moves ago. */
    public volatile String zoneText = "Diamond Reserve";
    public volatile boolean zoneGuard = true;
    /** Command run when stuck / nothing to hunt for {@link #idleWarpMs} (empty = just re-plan). */
    public volatile String warpCmd = "";
    public volatile long idleWarpMs = 8000;

    private final WalkCache cache;
    private final Planner planner = new Planner();
    private final HumanAim aim = new HumanAim();
    private final Random rand = new Random();

    private boolean running;
    private Phase phase = Phase.HUNT;
    private int wait, stepTicks;
    private long phaseSince;
    private Entity current;
    private int holeBefore = -1;
    /** The black hole was in hand this cycle (so it vanishing from the hotbar means it was placed). */
    private boolean holeHeld;
    /** Black hole being used while we already walk to the next zombie. */
    private boolean holePending;
    private long holeStart;
    private int holeUseTick, holeMisses;
    /** When the black hole we placed last is fully done (ms since epoch), 0 = none running. */
    private long holeEndsAt;
    /** Zombie we hit just before the hole, and the holes still waiting to see their zombie get sucked in. */
    private Entity lastHit;
    private final List<Entity> hitZombies = new ArrayList<>();
    private int killsSinceRefresh, kills;
    private long hitAt, holeUseStart, vanishAt;
    private boolean watchBack;
    private record PendingHole(Entity zombie, long placedAt) {}
    private final List<PendingHole> pendingHoles = new ArrayList<>();

    /** entity id -> time we hit it (never hit twice) or gave up on it. */
    private final Map<Integer, Long> tagged = new HashMap<>();

    public int hits, holesPlaced;

    // path following state
    private Planner.Leg leg;
    private Cursor legCursor;
    private long lastPlanRequest, noLegSince, arrivedAt, lastWarp, warpPauseUntil, idleSince;
    private int legStuck;
    private boolean planDirty = true;
    private final Map<BlockPos, Long> avoid = new HashMap<>();
    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    private Cursor wdCursor;
    private int wdIdx, wdTicks, recoverTicks;
    private double wdBest;
    private boolean moving;
    private Vec3 anchor;
    private long anchorTime;
    private int strafeDir, pulse;
    private float recoverYaw;
    private List<Entity> lastCands = List.of();

    // zone guard: one "move" = one finished hunt cycle (hit + black hole)
    private final ArrayDeque<Vec3> moves = new ArrayDeque<>();
    private Vec3 returnTo;
    private long returnSince, zoneGrace;
    private int zoneTick, outChecks;
    private boolean zoneOk = true;

    /** ESP: every zombie around us, with a colour kind (0 target, 1 current, 2 already hit). */
    public record Esp(Entity e, int kind) {}
    private volatile List<Esp> esp = List.of();

    public HuntController(WalkCache cache) {
        this.cache = cache;
        loadLearned();
    }

    // ---- auto-learning the black hole time ---------------------------------------------------------------

    private static Path learnedFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("huntingmacro.properties");
    }

    private void loadLearned() {
        Path f = learnedFile();
        if (!Files.isRegularFile(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties pr = new Properties();
            pr.load(in);
            double t = Double.parseDouble(pr.getProperty("holeTimeSec", "" + holeTimeSec));
            if (t >= 1.0 && t <= 60.0) holeTimeSec = t;
            holeSamples = Integer.parseInt(pr.getProperty("holeSamples", "0"));
            holeAuto = Boolean.parseBoolean(pr.getProperty("holeAuto", "true"));
            useLatencySec = Double.parseDouble(pr.getProperty("useLatencySec", "0"));
            cooldownSec = Double.parseDouble(pr.getProperty("cooldownSec", "0"));
            hitToHoleSec = Double.parseDouble(pr.getProperty("hitToHoleSec", "0"));
            timingSamples = Integer.parseInt(pr.getProperty("timingSamples", "0"));
            holeLeadManual = Boolean.parseBoolean(pr.getProperty("holeLeadManual", "false"));
            double lead = Double.parseDouble(pr.getProperty("holeLeadSec", "" + holeLeadSec));
            if (lead >= 0 && lead <= 5) holeLeadSec = lead;
        } catch (Exception ignored) {
            // bad file: keep the defaults
        }
    }

    private void saveLearned() {
        Properties pr = new Properties();
        pr.setProperty("holeTimeSec", String.format(Locale.ROOT, "%.2f", holeTimeSec));
        pr.setProperty("holeSamples", "" + holeSamples);
        pr.setProperty("holeAuto", "" + holeAuto);
        pr.setProperty("useLatencySec", String.format(Locale.ROOT, "%.3f", useLatencySec));
        pr.setProperty("cooldownSec", String.format(Locale.ROOT, "%.3f", cooldownSec));
        pr.setProperty("hitToHoleSec", String.format(Locale.ROOT, "%.3f", hitToHoleSec));
        pr.setProperty("timingSamples", "" + timingSamples);
        pr.setProperty("holeLeadManual", "" + holeLeadManual);
        pr.setProperty("holeLeadSec", String.format(Locale.ROOT, "%.3f", holeLeadSec));
        try (OutputStream out = Files.newOutputStream(learnedFile())) {
            pr.store(out, "Hunting Macro: learned values");
        } catch (IOException ignored) {
            // can't save: it just learns again next time
        }
    }

    public void saveSettings() {
        saveLearned();
    }

    /** Resets the learned time to the default guess. */
    public void forgetLearned() {
        holeTimeSec = 5.0;
        holeSamples = 0;
        useLatencySec = cooldownSec = hitToHoleSec = 0;
        timingSamples = 0;
        holeLeadSec = 0.5;
        holeLeadManual = false;
        watchBack = false;
        pendingHoles.clear();
        saveLearned();
    }

    /**
     * Every tick: a zombie we put a black hole next to that is now dead/gone tells us how long the hole needs.
     * Under 1 s means our own hit killed it, over 40 s is a fluke; both are ignored. The first sample replaces the
     * guess, later ones are blended in (70% old, 30% new).
     */
    /** A zombie we hit that is now dead (not just out of range) is a kill; every N kills the grey ones come back. */
    private void countKills() {
        if (hitZombies.isEmpty()) return;
        var it = hitZombies.iterator();
        while (it.hasNext()) {
            Entity z = it.next();
            if (z.isAlive()) continue;
            it.remove();
            Entity.RemovalReason why = z.getRemovalReason();
            if (why == Entity.RemovalReason.UNLOADED_TO_CHUNK || why == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                    || why == Entity.RemovalReason.CHANGED_DIMENSION) continue;
            kills++;
            killsSinceRefresh++;
            if (refreshEvery > 0 && killsSinceRefresh >= refreshEvery) refreshGrey(killsSinceRefresh + " kills");
        }
        if (hitZombies.size() > 200) hitZombies.subList(0, hitZombies.size() - 100).clear();
    }

    /** Forget which zombies were hit/skipped so every zombie shown by the ESP can be hunted again. */
    private void refreshGrey(String why) {
        killsSinceRefresh = 0;
        if (tagged.isEmpty()) return;
        int n = tagged.size();
        tagged.clear();
        Chat.msg("Refreshed " + n + " grey zombies (" + why + "): they can be hit again.");
    }

    private static double blend(double old, double sample) {
        return old <= 0 ? sample : old * 0.7 + sample * 0.3;
    }

    private void learnHoles(LocalPlayer p, long now) {
        if (watchBack && holeSlot(p) >= 0) { // the black hole is back in the hotbar: that's its cooldown
            watchBack = false;
            double c = (now - vanishAt) / 1000.0;
            if (holeAuto && c >= 0.3 && c <= 60.0) {
                cooldownSec = blend(cooldownSec, c);
                Chat.msg(String.format(Locale.ROOT, "Learned: the black hole is back after %.1fs (avg %.1fs)", c, cooldownSec));
                saveLearned();
            }
        }
        if (pendingHoles.isEmpty()) return;
        var it = pendingHoles.iterator();
        while (it.hasNext()) {
            PendingHole h = it.next();
            Entity z = h.zombie();
            if (now - h.placedAt() > 60_000) {
                it.remove();
                continue;
            }
            if (z.isAlive()) continue;
            it.remove();
            Entity.RemovalReason why = z.getRemovalReason();
            if (why == Entity.RemovalReason.UNLOADED_TO_CHUNK || why == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                    || why == Entity.RemovalReason.CHANGED_DIMENSION) continue; // walked out of range, not sucked in
            double sec = (now - h.placedAt()) / 1000.0;
            if (sec < 1.0 || sec > 40.0) continue;
            if (!holeAuto) continue;
            holeTimeSec = holeSamples == 0 ? sec : holeTimeSec * 0.7 + sec * 0.3;
            holeTimeSec = Math.max(1.0, Math.min(60.0, holeTimeSec));
            holeSamples++;
            Chat.msg(String.format(Locale.ROOT, "Learned: that hole took %.1fs, black hole time is now %.1fs (%d samples)",
                    sec, holeTimeSec, holeSamples));
            saveLearned();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Called every rendered frame so the camera moves smoothly. */
    public void frame(Minecraft mc) {
        if (!running || mc.player == null || mc.screen != null) return;
        aim.frame(mc, mc.player);
    }

    public boolean start(Minecraft mc) {
        if (mc.player == null) return false;
        if (findByName(mc.player, axeName) < 0) {
            Chat.msg("No item named \"" + axeName + "\" in your hotbar (prime hunt axe). Set it with /hunt axe <text>. Not started.");
            return false;
        }
        if (holeSlot(mc.player) < 0) {
            Chat.msg("Warning: no item named \"" + holeName + "\" in your hotbar; the black hole step will be skipped (/hunt hole name <text>). Hotbar: " + hotbarNames(mc.player));
        }
        if (golden != Golden.OFF && findGolden(mc.player) < 0) {
            Chat.msg("Warning: no golden axe in your hotbar; that step will be skipped.");
        }
        leg = null;
        legCursor = null;
        current = null;
        phase = Phase.HUNT;
        planDirty = true;
        lastPlanRequest = 0;
        avoid.clear();
        blacklist.clear();
        holePending = false;
        holeMisses = 0;
        holeEndsAt = 0;
        pendingHoles.clear();
        recoverTicks = 0;
        wdCursor = null;
        anchor = null;
        warpPauseUntil = 0;
        idleSince = 0;
        moves.clear();
        returnTo = null;
        outChecks = 0;
        zoneOk = true;
        zoneGrace = System.currentTimeMillis() + 2000;
        moves.addLast(mc.player.position());
        aim.reset();
        running = true;
        return true;
    }

    public void stop(Minecraft mc) {
        running = false;
        releaseKeys(mc);
    }

    // ---- main loop ----------------------------------------------------------------------------

    public void tick(Minecraft mc) {
        if (mc.level != null && mc.player != null && mc.level.getGameTime() % 4 == 0) updateEsp(mc);
        if (!running) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || (mc.screen != null && phase != Phase.RESCUE)) {
            releaseKeys(mc);
            return;
        }
        if (p.getHealth() < p.getMaxHealth() * 0.3f) {
            Chat.msg("Health below 30% - stopping.");
            stop(mc);
            return;
        }
        long now = System.currentTimeMillis();
        if (now < warpPauseUntil) {
            releaseKeys(mc);
            anchor = null;
            return;
        }
        learnHoles(p, now);
        countKills();
        tagged.values().removeIf(t -> now - t > TAG_MS);
        avoid.values().removeIf(t -> now - t > AVOID_MS);
        blacklist.values().removeIf(t -> now - t > BLACKLIST_MS);

        if (escapeLava(mc, p, now)) return;
        moving = false;
        switch (phase) {
            case HUNT -> hunt(mc, p, now);
            case HIT -> hit(mc, p, now);
            case GOLDEN -> goldenStep(mc, p, now);
            case RETURN -> returnStep(mc, p, now);
            case RESCUE -> rescueStep(mc, p, now);
        }
        if (phase == Phase.HUNT || phase == Phase.RETURN) stuckCheck(mc, p, now);
    }

    // ---- lava ----------------------------------------------------------------------------------------------------

    private long lavaSince;
    private boolean lavaMsg;
    private int lavaCount;

    /**
     * In lava: drop everything, hold jump (swim up) and walk to the nearest dry, safe standing cell within 6 blocks.
     * Gives up (stops the macro) after 15 s. Afterwards the old plan is thrown away and re-made; the planner
     * already keeps away from lava.
     */
    private boolean escapeLava(Minecraft mc, LocalPlayer p, long now) {
        if (phase == Phase.RESCUE && rescueStep < 2) return false; // warping out right now
        if (!p.isInLava()) {
            if (lavaSince != 0) {
                lavaSince = 0;
                lavaMsg = false;
                leg = null;
                legCursor = null;
                recoverTicks = 0;
                wdCursor = null;
                planDirty = true;
                lastPlanRequest = 0;
                releaseMovement(mc);
            }
            return false;
        }
        if (lavaSince == 0) {
            lavaSince = now;
            lavaCount++;
            if (lavaRescue && lavaCount > lavaLimit) { // too many times: don't fight it, warp out
                startRescue(mc, p, now);
                return true;
            }
        }
        if (!lavaMsg) {
            Chat.msg("In lava - getting out (" + lavaCount + "x).");
            lavaMsg = true;
        }
        if (now - lavaSince > 15_000) {
            Chat.msg("Stuck in lava for 15 s - stopping.");
            stop(mc);
            return true;
        }
        mc.options.keyAttack.setDown(false);
        BlockPos me = p.blockPosition(), best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -6; dz <= 6; dz++)
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos f = me.offset(dx, dy, dz);
                    double d = dx * dx + dz * dz + dy * dy * 2.0;
                    if (d < bd && safeStand(mc, f)) {
                        bd = d;
                        best = f;
                    }
                }
        float yaw = p.getYRot();
        if (best != null) {
            Vec3 c = best.getCenter();
            yaw = (float) (Math.atan2(c.z - p.getZ(), c.x - p.getX()) * 180.0 / Math.PI) - 90f;
        }
        aim.setTarget(yaw, 0f, 900f, 1.2f);
        float err = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - p.getYRot()));
        mc.options.keyUp.setDown(err < 100f);
        mc.options.keyJump.setDown(true);
        mc.options.keySprint.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        return true;
    }

    /** Dry cell we can stand in: air at feet and head, solid non-lava floor, no fluid, no magma. */
    private static boolean safeStand(Minecraft mc, BlockPos f) {
        var lvl = mc.level;
        BlockState feet = lvl.getBlockState(f), head = lvl.getBlockState(f.above()), floor = lvl.getBlockState(f.below());
        if (feet.is(Blocks.LAVA) || head.is(Blocks.LAVA) || floor.is(Blocks.LAVA) || floor.is(Blocks.MAGMA_BLOCK)
                || feet.is(Blocks.FIRE) || feet.is(Blocks.SOUL_FIRE)) return false;
        if (!lvl.getFluidState(f).isEmpty() || !lvl.getFluidState(f.above()).isEmpty()) return false;
        if (!feet.getCollisionShape(lvl, f).isEmpty() || !head.getCollisionShape(lvl, f.above()).isEmpty()) return false;
        return !floor.getCollisionShape(lvl, f.below()).isEmpty();
    }

    // ---- rescue: warp deep -> walk to the lift -> click the Lift Operator -> click the diamond -----------------------

    private int rescueStep;
    private long rescueAt, rescueSince;
    private int liftUseTick;

    private void startRescue(Minecraft mc, LocalPlayer p, long now) {
        lavaSince = 0;
        lavaMsg = false;
        releaseKeys(mc);
        holePending = false;
        current = null;
        leg = null;
        legCursor = null;
        recoverTicks = 0;
        wdCursor = null;
        Chat.msg("Stuck in lava " + lavaCount + " times - /" + rescueCmd + ", then the lift at "
                + liftX + " " + liftY + " " + liftZ + ".");
        rescueStep = 0;
        setPhase(Phase.RESCUE, now);
    }

    private void rescueStep(Minecraft mc, LocalPlayer p, long now) {
        switch (rescueStep) {
            case 0 -> { // warp
                p.connection.sendCommand(rescueCmd);
                rescueAt = now;
                rescueStep = 1;
            }
            case 1 -> { // wait for the teleport and the chunks
                releaseKeys(mc);
                if (now - rescueAt > 5000 && cache.grid() != null) {
                    startRescueSteps(now, 2);
                    avoid.clear();
                    leg = null;
                    legCursor = null;
                    planDirty = true;
                    lastPlanRequest = 0;
                }
            }
            case 2 -> liftWalk(mc, p, now);
            case 3 -> { // click the Lift Operator until its menu is open
                releaseMovement(mc);
                if (mc.screen instanceof AbstractContainerScreen<?>) {
                    rescueStep = 4;
                    rescueAt = now;
                    return;
                }
                Entity npc = findLiftNpc(mc, p);
                if (npc == null) {
                    if (now - rescueAt > 12_000) {
                        Chat.msg("Can't find \"" + liftNpc + "\" near " + liftX + " " + liftY + " " + liftZ
                                + " (/hunt lift npc <text>) - stopping.");
                        stop(mc);
                    }
                    return;
                }
                float[] ang = anglesTo(p, centre(npc));
                aim.setTarget(ang[0], ang[1], 500f, 1f);
                boolean onIt = aim.settled(p, 4f) && mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == npc;
                if ((onIt || now - rescueAt > 3000) && liftUseTick++ % 20 == 0) { // retry every second
                    mc.gameMode.interact(p, npc, new EntityHitResult(npc), InteractionHand.MAIN_HAND);
                    p.swing(InteractionHand.MAIN_HAND);
                }
                if (now - rescueAt > 25_000) {
                    Chat.msg("The lift menu never opened - stopping.");
                    stop(mc);
                }
            }
            case 4 -> { // click the diamond
                if (!(mc.screen instanceof AbstractContainerScreen<?> cs)) {
                    rescueStep = 3; // menu closed on us: open it again
                    rescueAt = now;
                    return;
                }
                if (now - rescueAt < 400) return; // let the server fill the menu
                var menu = cs.getMenu();
                int n = menu.slots.size() - 36; // the last 36 slots are our own inventory
                for (int i = 0; i < n; i++) {
                    if (menu.slots.get(i).getItem().is(Items.DIAMOND)) {
                        mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.PICKUP, p);
                        rescueStep = 5;
                        rescueAt = now;
                        return;
                    }
                }
                if (now - rescueAt > 6000) {
                    Chat.msg("No diamond in the lift menu - stopping.");
                    p.closeContainer();
                    stop(mc);
                }
            }
            default -> { // clicked: close the menu and go back to hunting
                if (now - rescueAt < 800) return;
                if (mc.screen != null) p.closeContainer();
                if (now - rescueAt < 1500) return;
                Chat.msg("Lift taken - hunting again.");
                lavaCount = 0;
                moves.clear();
                leg = null;
                legCursor = null;
                planDirty = true;
                holeEndsAt = 0;
                zoneGrace = now + 6000;
                outChecks = 0;
                int axe = findByName(p, axeName);
                if (axe >= 0) p.getInventory().setSelectedSlot(axe);
                setPhase(Phase.HUNT, now);
            }
        }
    }

    // The path map only reaches ~45 blocks around us and the planner has a cost limit, so a lift further away is
    // approached in hops: each hop goes to the standable spot nearest to the lift that the planner can reach.
    private final List<BlockPos> hopFailed = new ArrayList<>();
    private BlockPos hopTarget;
    private long hopAsked, rescueNoLeg, rescueReport;

    private long deepCheck;

    private void liftWalk(Minecraft mc, LocalPlayer p, long now) {
        Vec3 goal = new Vec3(liftX + 0.5, liftY, liftZ + 0.5);
        double dist = hDist(p.position(), goal);
        if (dist < 3.0 && Math.abs(p.getY() - liftY) < 3.5) {
            releaseMovement(mc);
            rescueStep = 3;
            rescueAt = now;
            liftUseTick = 0;
            aim.retarget();
            return;
        }
        // already in Deep Caverns (sidebar location): no lift needed, hunt the closest zombie
        if (!deepText.isBlank() && now - deepCheck > 1000) {
            deepCheck = now;
            if (Boolean.TRUE.equals(Zone.contains(mc, deepText))) {
                Chat.msg("Location is \"" + deepText + "\" - skipping the lift, hunting the closest zombie.");
                endRescue(mc, p, now);
                return;
            }
        }
        if (now - rescueSince > 120_000) {
            Chat.msg("Couldn't walk to the lift in 120 s (" + Planner.lastStats + ") - stopping.");
            stop(mc);
            return;
        }
        hopWalk(mc, p, now, goal, "Lift walk");
    }

    /** Leaves the rescue (lift done or skipped) and goes back to hunting with a clean slate. */
    private void endRescue(Minecraft mc, LocalPlayer p, long now) {
        lavaCount = 0;
        moves.clear();
        leg = null;
        legCursor = null;
        planDirty = true;
        holeEndsAt = 0;
        zoneGrace = now + 6000;
        outChecks = 0;
        resetHops();
        int axe = findByName(p, axeName);
        if (axe >= 0) p.getInventory().setSelectedSlot(axe);
        setPhase(Phase.HUNT, now);
    }

    private void resetHops() {
        hopFailed.clear();
        hopTarget = null;
        rescueNoLeg = 0;
        rescueReport = 0;
    }

    /**
     * Walks toward {@code goal} (any spot, even outside the ~45 block path map) in hops: each hop is the standable
     * cell nearest to the goal that the planner can reach. Finished hops are never revisited.
     */
    private void hopWalk(Minecraft mc, LocalPlayer p, long now, Vec3 goal, String label) {
        double dist = hDist(p.position(), goal);
        if (now - rescueReport > 5000) { // progress report, so a failure can be diagnosed
            rescueReport = now;
            Chat.msg(String.format(Locale.ROOT, "%s: %.0f blocks to go (dy %.0f), target %s, %s | planner: %s",
                    label, dist, goal.y - p.getY(), hopTarget == null ? "none" : hopTarget.toShortString(),
                    leg == null ? "no path" : "walking", Planner.lastStats));
        }

        // reached this hop (but it isn't the goal): don't come back here, pick the next one
        if (hopTarget != null) {
            Vec3 hc = hopTarget.getCenter();
            if (hDist(p.position(), hc) < 3.5 && Math.abs(p.getY() - hopTarget.getY()) < 3.5) {
                Chat.msg(label + ": reached " + hopTarget.toShortString() + ", " + String.format(Locale.ROOT, "%.0f", dist)
                        + " blocks to go, picking the next spot.");
                hopFailed.add(hopTarget);
                hopTarget = null;
                leg = null;
                legCursor = null;
                rescueNoLeg = 0;
            }
        }

        // this hop failed (the planner found no way to it): remember it and pick another
        if (leg == null && hopTarget != null && now - hopAsked > 1500
                && (Planner.lastStats.startsWith("no reachable") || Planner.lastStats.startsWith("no mushrooms"))) {
            hopFailed.add(hopTarget);
            hopTarget = null;
        }
        if (hopTarget == null || (leg == null && now - hopAsked > 4000)) {
            hopTarget = chooseHop(p, goal);
            hopAsked = now;
            planDirty = true;
            lastPlanRequest = 0;
        }

        if (hopTarget == null) { // no map yet / nothing standable: blind steer toward the goal
            blindWalk(mc, p, goal);
            return;
        }
        if (leg == null) {
            if (rescueNoLeg == 0) rescueNoLeg = now;
            if (now - rescueNoLeg > 8000) { // the planner can't help: just head for it
                blindWalk(mc, p, goal);
                return;
            }
        } else {
            rescueNoLeg = 0;
        }
        moving = true;
        navigateTo(mc, p, now, List.of(hopTarget), List.of());
    }

    /** Standable cell (in the path map) closest to the lift, skipping spots near failed hops. */
    private BlockPos chooseHop(LocalPlayer p, Vec3 goal) {
        var g = cache.grid();
        if (g == null) return null;
        Vec3 pos = p.position();
        double direct = hDist(pos, goal);
        if (hopFailed.isEmpty() && direct <= 30 && Math.abs(goal.y - pos.y) <= 12)
            return BlockPos.containing(goal.x, goal.y, goal.z); // close enough to plan straight to it
        int px = (int) Math.floor(pos.x), py = (int) Math.floor(pos.y), pz = (int) Math.floor(pos.z);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int x = px - 42; x <= px + 42; x += 2)
            for (int z = pz - 42; z <= pz + 42; z += 2)
                for (int y = py - 14; y <= py + 14; y++) {
                    if (!g.standable(x, y, z)) continue;
                    double d = Math.hypot(goal.x - (x + 0.5), goal.z - (z + 0.5)) + 1.5 * Math.abs(goal.y - y);
                    if (d >= bd) continue;
                    boolean bad = false;
                    for (BlockPos f : hopFailed)
                        if (Math.abs(f.getX() - x) <= 6 && Math.abs(f.getZ() - z) <= 6 && Math.abs(f.getY() - y) <= 6) {
                            bad = true;
                            break;
                        }
                    if (bad) continue;
                    bd = d;
                    best = new BlockPos(x, y, z);
                }
        if (best == null && !hopFailed.isEmpty()) hopFailed.clear(); // tried everything: start over
        return best;
    }

    /** Last resort: face the lift, run, and jump when something is in the way. */
    private void blindWalk(Minecraft mc, LocalPlayer p, Vec3 goal) {
        float yaw = (float) (Math.atan2(goal.z - p.getZ(), goal.x - p.getX()) * 180.0 / Math.PI) - 90f;
        aim.setTarget(yaw, 6f, WALK_TURN_SPEED, 1.0f);
        float err = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - p.getYRot()));
        mc.options.keyUp.setDown(err < 60f);
        mc.options.keySprint.setDown(err < 30f);
        mc.options.keyJump.setDown(p.onGround() && p.horizontalCollision);
        moving = true;
    }

    /** /hunt lift go [warp]: run just the lift trip from where you stand (for testing, no lava needed). */
    public void testLift(Minecraft mc, boolean warp) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        long now = System.currentTimeMillis();
        running = true;
        holePending = false;
        current = null;
        leg = null;
        legCursor = null;
        avoid.clear();
        recoverTicks = 0;
        wdCursor = null;
        planDirty = true;
        lastPlanRequest = 0;
        aim.reset();
        Chat.msg("Lift test " + (warp ? "with /" + rescueCmd : "from here") + ". /hunt stop cancels.");
        startRescueSteps(now, warp ? 0 : 2);
    }

    private void startRescueSteps(long now, int step) {
        hopFailed.clear();
        hopTarget = null;
        rescueNoLeg = 0;
        rescueReport = 0;
        rescueSince = now;
        rescueStep = step;
        setPhase(Phase.RESCUE, now);
    }

    /** The NPC behind the "Lift Operator" name tag (name tags are armor stands; the NPC stands right there). */
    private Entity findLiftNpc(Minecraft mc, LocalPlayer p) {
        List<Entity> all = mc.level.getEntities((Entity) null, p.getBoundingBox().inflate(12));
        String want = liftNpc.toLowerCase(Locale.ROOT);
        Entity tag = null;
        for (Entity e : all) {
            var n = e.getCustomName();
            if (n == null || !cleanName(n.getString()).toLowerCase(Locale.ROOT).contains(want)) continue;
            if (e.getType() != EntityType.ARMOR_STAND) return e; // the NPC carries the name itself
            if (tag == null || e.distanceToSqr(p) < tag.distanceToSqr(p)) tag = e;
        }
        if (tag == null) return null;
        Entity best = null;
        double bd = 9.0; // within 3 blocks of the tag
        for (Entity e : all) {
            if (e == p || e == tag || !(e instanceof LivingEntity) || e.getType() == EntityType.ARMOR_STAND) continue;
            double d = e.distanceToSqr(tag.position());
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private void setPhase(Phase ph, long now) {
        phase = ph;
        phaseSince = now;
        stepTicks = 0;
    }

    // ---- finding zombies -----------------------------------------------------------------------

    /** Untouched filter-matching zombies in range that are not standing in a black hole, nearest first. */
    private List<Entity> candidates(Minecraft mc, LocalPlayer p, long now) {
        List<Entity> all = mc.level.getEntities((Entity) null, scanBox(p));
        String filter = mobFilter.toLowerCase(Locale.ROOT);
        List<Entity> out = new ArrayList<>();
        for (Entity e : all) {
            if (!wanted(e, all, filter) || tagged.containsKey(e.getId())) continue;
            out.add(e);
        }
        Vec3 me = p.position();
        out.sort((a, b) -> Double.compare(a.distanceToSqr(me), b.distanceToSqr(me)));
        return out;
    }

    /** The one rule for "is this a target": used by both the hunter and the ESP so they always agree. */
    private boolean wanted(Entity e, List<Entity> all, String filter) {
        if (e.getType() != EntityType.ZOMBIE || !e.isAlive() || !inArea(e)) return false;
        return filter.isEmpty() || nameMatches(e, all, filter);
    }

    /** Where to look for entities: the fixed area (plus a margin) or a box around us. */
    private AABB scanBox(LocalPlayer p) {
        if (!areaOn) return p.getBoundingBox().inflate(range, Math.min(range, 30), range);
        return new AABB(Math.min(areaX1, areaX2) - 1, areaY1 - 4, Math.min(areaZ1, areaZ2) - 1,
                Math.max(areaX1, areaX2) + 2, areaY2 + 5, Math.max(areaZ1, areaZ2) + 2);
    }

    /** Inside the fixed area (a block of slack in Y so zombies standing on the edge layers still count). */
    private boolean inArea(Entity e) {
        if (!areaOn) return true;
        double x = e.getX(), y = e.getY(), z = e.getZ();
        return x >= Math.min(areaX1, areaX2) && x < Math.max(areaX1, areaX2) + 1
                && z >= Math.min(areaZ1, areaZ2) && z < Math.max(areaZ1, areaZ2) + 1
                && y >= Math.min(areaY1, areaY2) - 1 && y < Math.max(areaY1, areaY2) + 2;
    }

    /** "12.5M/20M❤", "1,200❤", "❤ 300", "(50%)" etc: the health part of a SkyBlock name tag. */
    private static final Pattern HEALTH = Pattern.compile(
            "(?i)[\\[(]?\\s*[\\d.,]+\\s*[kmbt]?\\s*(/\\s*[\\d.,]+\\s*[kmbt]?)?\\s*[❤♥][\\])]?|[❤♥]\\s*[\\d.,]+\\s*[kmbt]?(/\\s*[\\d.,]+\\s*[kmbt]?)?");

    /** Name tag text with colour codes and the health number removed, e.g. "[Lv100] Miner Zombie". */
    static String cleanName(String raw) {
        String t = raw.replaceAll("§.", "");
        t = HEALTH.matcher(t).replaceAll(" ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** SkyBlock mobs carry their name on a floating armor stand just above them (or on the mob itself). */
    private static boolean nameMatches(Entity zombie, List<Entity> all, String filter) {
        var own = zombie.getCustomName();
        if (own != null && cleanName(own.getString()).toLowerCase(Locale.ROOT).contains(filter)) return true;
        AABB near = zombie.getBoundingBox().inflate(0.9, 2.6, 0.9);
        for (Entity e : all) {
            if (e.getType() != EntityType.ARMOR_STAND || !near.contains(e.position())) continue;
            var n = e.getCustomName();
            if (n != null && cleanName(n.getString()).toLowerCase(Locale.ROOT).contains(filter)) return true;
        }
        return false;
    }

    /** Names of zombies and nearby named armor stands, for /hunt scan. */
    public void scan(Minecraft mc) {
        var p = mc.player;
        if (p == null || mc.level == null) return;
        List<Entity> all = mc.level.getEntities((Entity) null, p.getBoundingBox().inflate(30, 15, 30));
        int z = 0;
        for (Entity e : all) {
            if (e.getType() == EntityType.ZOMBIE) {
                z++;
                var n = e.getCustomName();
                Chat.msg("zombie id " + e.getId() + " " + String.format("%.1f", e.distanceTo(p)) + "m"
                        + (n != null ? " name=" + cleanName(n.getString()) : ""));
            } else if (e.getType() == EntityType.ARMOR_STAND && e.getCustomName() != null) {
                Chat.msg("tag: " + cleanName(e.getCustomName().getString()));
            }
        }
        Chat.msg(z + " zombies within 30 blocks. Mob filter is \"" + mobFilter + "\" (/hunt mob <text>).");
    }

    // ---- HUNT: walk to the next zombie -----------------------------------------------------------

    private void hunt(Minecraft mc, LocalPlayer p, long now) {
        if (checkZone(mc, p, now)) return;
        if (holePending) handleHole(mc, p, now);
        if (!running) return;
        List<Entity> cands = candidates(mc, p, now);
        lastCands = cands;

        if (cands.isEmpty()) {
            leg = null;
            legCursor = null;
            releaseMovement(mc);
            if (idleSince == 0) idleSince = now;
            else if (now - idleSince >= idleWarpMs && now - lastWarp >= 20_000) {
                doWarp(mc, p, now, "No zombies to hunt");
            }
            // grey (already hit) zombies are all that's left: wake them up instead of standing around
            if (idleSince != 0 && now - idleSince > 3000) {
                for (Esp e : esp) if (e.kind() == 2) { refreshGrey("only already-hit zombies left"); break; }
            }
            // none loaded: head for the scan area so they come into view
            if (areaOn && idleSince != 0 && now - idleSince > 2000) {
                Vec3 c = new Vec3((areaX1 + areaX2) / 2.0 + 0.5, (areaY1 + areaY2) / 2.0, (areaZ1 + areaZ2) / 2.0 + 0.5);
                if (hDist(p.position(), c) > 12) hopWalk(mc, p, now, c, "Walking to the scan area");
            }
            return;
        }
        idleSince = 0;
        if (hopTarget != null && p.getBoundingBox().inflate(PLAN_RADIUS).contains(cands.get(0).position())) resetHops();

        // close enough with a clear line: stop and hit it
        Entity near = cands.get(0);
        if (!holePending && inMelee(mc, p, near)) {
            // previous black hole still working: stand by the zombie until it is (almost) done
            long backAt = watchBack && cooldownSec > 0 ? vanishAt + (long) (cooldownSec * 1000) - (long) (hitToHoleSec * 1000) : 0;
            if (now < Math.max(holeEndsAt - (long) (holeLeadSec * 1000), backAt)) {
                releaseMovement(mc);
                float[] ang = anglesTo(p, centre(near));
                aim.setTarget(ang[0], ang[1], 400f, 1f);
                return;
            }
            current = near;
            releaseMovement(mc);
            aim.retarget();
            wait = 2 + rand.nextInt(3);
            setPhase(Phase.HIT, now);
            return;
        }
        navigate(mc, p, now, cands);
    }

    private static Vec3 centre(Entity e) {
        return e.getBoundingBox().getCenter();
    }

    private boolean inMelee(Minecraft mc, LocalPlayer p, Entity e) {
        Vec3 eyes = p.getEyePosition();
        Vec3 c = centre(e);
        if (eyes.distanceTo(c) > HIT_DIST) return false;
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, c, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS;
    }

    // ---- HIT: one hit with the prime hunt axe --------------------------------------------------

    private void hit(Minecraft mc, LocalPlayer p, long now) {
        releaseMovement(mc);
        if (current == null || !current.isAlive() || p.getEyePosition().distanceTo(centre(current)) > HIT_DIST + 1.0) {
            current = null;
            setPhase(Phase.HUNT, now);
            return;
        }
        int slot = findByName(p, axeName);
        if (slot < 0) {
            Chat.msg("Lost the \"" + axeName + "\" item - stopping.");
            stop(mc);
            return;
        }
        if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);

        float[] ang = anglesTo(p, centre(current));
        aim.setTarget(ang[0], ang[1], 700f, 1f);
        if (wait-- > 0) return;

        boolean onIt = aim.settled(p, 3.5f) && mc.hitResult instanceof EntityHitResult ehr
                && ehr.getEntity() == current;
        if (onIt) {
            mc.gameMode.attack(p, current);
            p.swing(InteractionHand.MAIN_HAND);
            tagged.put(current.getId(), now);
            lastHit = current;
            hitZombies.add(current);
            hitAt = now;
            hits++;
            wait = 0; // black hole right away
            setPhase(Phase.GOLDEN, now);
        } else if (now - phaseSince > HIT_TIMEOUT_MS) {
            tagged.put(current.getId(), now - TAG_MS + 8000); // retry in 8 s
            current = null;
            setPhase(Phase.HUNT, now);
        }
    }

    // ---- GOLDEN: golden axe ------------------------------------------------------------------------

    private void goldenStep(Minecraft mc, LocalPlayer p, long now) {
        releaseMovement(mc);
        if (wait-- > 0) return;
        int slot = golden == Golden.OFF ? -1 : findGolden(p);
        if (slot < 0) {
            beginHole(now);
            return;
        }
        switch (stepTicks++) {
            case 0 -> {
                p.getInventory().setSelectedSlot(slot);
                wait = 3 + rand.nextInt(4);
            }
            case 1 -> {
                if (golden == Golden.USE) {
                    mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    p.swing(InteractionHand.MAIN_HAND);
                } else if (current != null && current.isAlive()
                        && p.getEyePosition().distanceTo(centre(current)) <= HIT_DIST + 0.5) {
                    mc.gameMode.attack(p, current);
                    p.swing(InteractionHand.MAIN_HAND);
                }
                wait = 4 + rand.nextInt(5);
            }
            default -> beginHole(now);
        }
    }

    // ---- BLACK HOLE: plain right click, while already walking to the next zombie ---------------------------------

    /** Hands over to the HUNT loop; {@link #handleHole} keeps using the hole until it is gone. */
    private void beginHole(long now) {
        holePending = true;
        holeStart = now;
        holeHeld = false;
        holeBefore = -1;
        holeUseTick = 0;
        current = null;
        setPhase(Phase.HUNT, now);
    }

    /**
     * Every tick while pending: hold the hole and right click in the air every 3 ticks. It counts as placed once
     * it vanished from the hotbar (or its stack shrank). Walking to the next zombie goes on meanwhile.
     */
    private void handleHole(Minecraft mc, LocalPlayer p, long now) {
        int slot = holeSlot(p);
        if (slot < 0) {
            if (holeHeld) { // it disappeared from the hotbar: placed
                holeDone(mc, p, now, true, true);
            } else if (now - holeStart > 4000) { // never showed up (still gone from the last one?)
                Chat.msg("No black hole in the hotbar (looked for \"" + holeName + "\" / \"black hole\"). Hotbar: "
                        + hotbarNames(p));
                holeDone(mc, p, now, false, false);
            }
            return;
        }
        if (p.getInventory().getSelectedSlot() != slot) {
            p.getInventory().setSelectedSlot(slot);
            holeBefore = p.getInventory().getItem(slot).getCount();
            holeStart = now; // the 1.5 s try starts once it is in hand
            holeUseTick = 0;
            return;
        }
        holeHeld = true;
        int count = p.getInventory().getItem(slot).getCount();
        if (holeBefore < 0) holeBefore = count;
        if (count < holeBefore) {
            holeDone(mc, p, now, true, false);
            return;
        }
        // give up after 3x the usual delay (learned), at least 1.5 s
        if (now - holeStart > Math.max(1500L, (long) (useLatencySec * 3000))) {
            holeDone(mc, p, now, false, false);
            return;
        }
        if (holeUseTick++ % 3 == 0) {
            if (holeUseStart == 0) holeUseStart = now;
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * After every hit that ended in a placed hole: how long the hole took to register (first right click ->
     * gone), how long hit -> placed took, and (from the hotbar watcher) its cooldown. The "hit before the old hole
     * ends" lead follows hit -> placed so the new hole goes down just as the old one finishes.
     */
    private void learnTimings(long now, boolean vanished) {
        if (holeUseStart != 0) {
            double u = (now - holeUseStart) / 1000.0;
            if (u >= 0.02 && u <= 5.0) useLatencySec = blend(useLatencySec, u);
        }
        if (hitAt != 0) {
            double h = (now - hitAt) / 1000.0;
            if (h >= 0.1 && h <= 5.0) {
                hitToHoleSec = blend(hitToHoleSec, h);
                if (!holeLeadManual) holeLeadSec = Math.max(0.2, Math.min(2.0, hitToHoleSec + 0.1));
            }
        }
        if (vanished) { // start the stopwatch for when it comes back
            vanishAt = now;
            watchBack = true;
        }
        timingSamples++;
        Chat.msg(String.format(Locale.ROOT,
                "Learned timings (%d): hit->hole %.2fs, hole registers in %.2fs, hit lead %.2fs%s",
                timingSamples, hitToHoleSec, useLatencySec, holeLeadSec, holeLeadManual ? " (manual)" : ""));
        saveLearned();
    }

    /** Back to the Prime Huntaxe; keeps the current path so we don't stop walking. */
    private void holeDone(Minecraft mc, LocalPlayer p, long now, boolean ok, boolean vanished) {
        holePending = false;
        if (ok) {
            holesPlaced++;
            if (holeAuto) learnTimings(now, vanished);
            holeEndsAt = now + (long) (holeTimeSec * 1000); // the background timer: this hole is busy until then
            if (holeAuto && lastHit != null) pendingHoles.add(new PendingHole(lastHit, now));
        } else {
            holeMisses++; // not used this time: no timer, the next zombie isn't held back
        }
        holeUseStart = 0;
        if (zoneOk) { // remember where this move ended (only spots inside the zone)
            moves.addLast(p.position());
            while (moves.size() > 12) moves.removeFirst();
        }
        int axe = findByName(p, axeName);
        if (axe >= 0 && p.getInventory().getSelectedSlot() != axe) p.getInventory().setSelectedSlot(axe);
        planDirty = true;
    }

    // ---- items --------------------------------------------------------------------------------------------

    /** Hotbar item names, for /hunt items and error messages. */
    public static String hotbarNames(LocalPlayer p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            sb.append(i + 1).append(": ").append(st.isEmpty() ? "-" : st.getHoverName().getString());
            if (i < 8) sb.append(" | ");
        }
        return sb.toString();
    }

    private int holeSlot(LocalPlayer p) {
        int s = findByName(p, holeName);
        return s >= 0 ? s : findByName(p, "black hole");
    }

    private static int findByName(LocalPlayer p, String text) {
        if (text == null || text.isBlank()) return -1;
        String t = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.getHoverName().getString().toLowerCase(Locale.ROOT).contains(t)) return i;
        }
        return -1;
    }

    private static int findGolden(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.is(Items.GOLDEN_AXE)) return i;
        }
        return -1;
    }

    // ---- navigation ---------------------------------------------------------------------------------------

    private void navigate(Minecraft mc, LocalPlayer p, long now, List<Entity> cands) {
        List<BlockPos> ts = new ArrayList<>();
        List<Entity> reachable = new ArrayList<>();
        for (Entity e : cands) {
            if (Math.abs(e.getX() - p.getX()) > PLAN_RADIUS || Math.abs(e.getZ() - p.getZ()) > PLAN_RADIUS) continue;
            reachable.add(e);
            BlockPos bp = BlockPos.containing(e.getX(), e.getY(), e.getZ());
            if (!blacklist.containsKey(bp)) ts.add(bp);
        }
        if (reachable.isEmpty() && !cands.isEmpty()) { // zombies exist but all are beyond the path map: hop toward the closest
            hopWalk(mc, p, now, cands.get(0).position(), "Walking to zombie");
            return;
        }
        if (hopTarget != null) resetHops(); // back to normal pathing
        navigateTo(mc, p, now, ts, reachable);
    }

    private void navigateTo(Minecraft mc, LocalPlayer p, long now, List<BlockPos> targets, List<Entity> cands) {
        Planner.Plan plan = planner.poll();
        if (plan != null) accept(plan, p);

        long interval = leg == null ? 400 : 600; // zombies move: keep the goal fresh
        if ((planDirty || now - lastPlanRequest > interval) && now - lastPlanRequest > 250) requestPlan(p, now, targets);

        if (leg == null) {
            releaseMovement(mc);
            if (noLegSince == 0) noLegSince = now;
            else if (now - noLegSince > 5000) { // nothing reachable: give those zombies a rest
                for (Entity e : cands) tagged.put(e.getId(), now - TAG_MS + 20_000);
                noLegSince = 0;
                planDirty = true;
            }
            return;
        }
        noLegSince = 0;

        validateLeg(p, now);
        Follow r = follow(mc, p, legCursor);
        if (r == Follow.ARRIVED) {
            if (arrivedAt == 0) arrivedAt = now;
            planDirty = true;                 // the zombie moved on: aim at where it is now
            if (now - arrivedAt > 2500 && !cands.isEmpty()) { // standing at the spot but can't get a hit in
                tagged.put(cands.get(0).getId(), now - TAG_MS + 10_000);
                arrivedAt = 0;
                leg = null;
            }
        } else {
            arrivedAt = 0;
            if (r == Follow.STUCK) {
                legStuck++;
                planDirty = true;
            }
        }
    }

    private void requestPlan(LocalPlayer p, long now, List<BlockPos> ts) {
        Set<BlockPos> keep = leg == null ? Set.of() : new HashSet<>(leg.targets());
        if (planner.request(cache.grid(), p.blockPosition(), ts, keep, new HashSet<>(avoid.keySet()))) {
            lastPlanRequest = now;
            planDirty = false;
        }
    }

    private void accept(Planner.Plan plan, LocalPlayer p) {
        if (plan.legs().isEmpty()) {
            leg = null;
            legCursor = null;
            return;
        }
        Planner.Leg first = plan.legs().get(0);
        if (leg != null && first.stand().equals(leg.stand())) { // same stand spot: keep walking smoothly
            leg = first;
            return;
        }
        leg = first;
        beginLeg(first, p);
    }

    // ---- stuck handling -----------------------------------------------------------------------------------------

    /** Trying to walk but staying inside a few blocks for a long time -> forget the plan, maybe warp. */
    private void stuckCheck(Minecraft mc, LocalPlayer p, long now) {
        Vec3 pos = p.position();
        if (!moving || anchor == null || hDist(pos, anchor) > STUCK_RADIUS || Math.abs(pos.y - anchor.y) > 4) {
            anchor = pos;
            anchorTime = now;
        } else if (now - anchorTime >= STUCK_MS) {
            anchorTime = now;
            if (!warpCmd.isBlank() && now - lastWarp >= 20_000) {
                doWarp(mc, p, now, "Stuck for " + STUCK_MS / 1000 + "s");
            } else {
                Chat.msg("Stuck - re-planning.");
                avoid.clear();
                leg = null;
                legCursor = null;
                planDirty = true;
            }
        }
    }

    private void doWarp(Minecraft mc, LocalPlayer p, long now, String why) {
        lastWarp = now;
        leg = null;
        legCursor = null;
        avoid.clear();
        recoverTicks = 0;
        wdCursor = null;
        anchor = null;
        idleSince = 0;
        planDirty = true;
        if (warpCmd.isBlank()) return;
        Chat.msg(why + " - /" + warpCmd);
        releaseKeys(mc);
        p.connection.sendCommand(warpCmd);
        warpPauseUntil = now + 1200;
    }

    // ---- rendering helpers ------------------------------------------------------------------------------------------

    public Planner.Leg currentLeg() {
        return running ? leg : null;
    }

    public List<Entity> candidatesSnapshot() {
        return running ? lastCands : List.of();
    }

    public Entity currentTarget() {
        return running ? current : null;
    }

    // ---- zone guard --------------------------------------------------------------------------------------

    /** Every ~0.5 s: if the sidebar says we left the zone, go back to where we were 3 moves ago. */
    private boolean checkZone(Minecraft mc, LocalPlayer p, long now) {
        if (!zoneGuard || zoneText.isBlank() || now < zoneGrace || ++zoneTick % 10 != 0) return false;
        Boolean in = Zone.contains(mc, zoneText);
        if (in == null) return false; // loading screen / no SkyBlock sidebar: can't tell
        if (!in && !deepText.isBlank() && Boolean.TRUE.equals(Zone.contains(mc, deepText))) in = true; // Deep Caverns counts
        zoneOk = in;
        if (in) {
            outChecks = 0;
            return false;
        }
        if (++outChecks < 2) return false; // must be out for two checks in a row
        outChecks = 0;
        Vec3[] arr = moves.toArray(new Vec3[0]);
        if (arr.length == 0) {
            Chat.msg("Left \"" + zoneText + "\" and there is no earlier spot to return to - stopping.");
            stop(mc);
            return true;
        }
        returnTo = arr[Math.max(0, arr.length - 3)];
        returnSince = now;
        leg = null;
        legCursor = null;
        planDirty = true;
        releaseMovement(mc);
        Chat.msg("Left \"" + zoneText + "\" - walking back to where I was 3 moves ago.");
        setPhase(Phase.RETURN, now);
        return true;
    }

    private void returnStep(Minecraft mc, LocalPlayer p, long now) {
        if (returnTo == null) {
            setPhase(Phase.HUNT, now);
            return;
        }
        if (hDist(p.position(), returnTo) < 3.5 && Math.abs(p.getY() - returnTo.y) < 4) {
            returnTo = null;
            leg = null;
            legCursor = null;
            planDirty = true;
            zoneGrace = now + 3000; // let the sidebar catch up
            outChecks = 0;
            setPhase(Phase.HUNT, now);
            return;
        }
        if (now - returnSince > 40_000) {
            Chat.msg("Couldn't get back into the zone in 40s - stopping.");
            stop(mc);
            return;
        }
        navigateTo(mc, p, now, List.of(BlockPos.containing(returnTo.x, returnTo.y, returnTo.z)), List.of());
    }

    // ---- ESP -------------------------------------------------------------------------------------------------------

    private void updateEsp(Minecraft mc) {
        LocalPlayer p = mc.player;
        List<Entity> all = mc.level.getEntities((Entity) null, scanBox(p));
        String filter = mobFilter.toLowerCase(Locale.ROOT);
        List<Esp> out = new ArrayList<>();
        for (Entity e : all) {
            if (!wanted(e, all, filter)) continue;
            int kind = e == current ? 1 : tagged.containsKey(e.getId()) ? 2 : 0;
            out.add(new Esp(e, kind));
        }
        esp = out;
    }

    public List<Esp> esp() {
        return esp;
    }

    public String status() {
        return (running ? "RUNNING " + phase : "stopped") + " | area=" + (areaOn ? "x " + areaX1 + ".." + areaX2 + ", z " + areaZ1 + ".." + areaZ2 + ", y " + areaY1 + ".." + areaY2 : "off") + " | lava=" + lavaCount + "/" + lavaLimit + " | zone=\"" + (zoneGuard ? zoneText : "off") + "\" in=" + zoneOk + " | hits=" + hits + " holes=" + holesPlaced + " missed=" + holeMisses + " holeTime=" + String.format(Locale.ROOT, "%.1fs", holeTimeSec) + (holeAuto ? " (auto, " + holeSamples + " samples)" : " (manual)") + " learned[hit->hole=" + String.format(Locale.ROOT, "%.2f", hitToHoleSec) + "s register=" + String.format(Locale.ROOT, "%.2f", useLatencySec) + "s cooldown=" + String.format(Locale.ROOT, "%.1f", cooldownSec) + "s lead=" + String.format(Locale.ROOT, "%.2f", holeLeadSec) + "s n=" + timingSamples + "]" + " kills=" + kills + " holeTimer="
                + (holeEndsAt > System.currentTimeMillis() ? String.format("%.1fs left", (holeEndsAt - System.currentTimeMillis()) / 1000.0) : "idle")
                + " | zombies in view=" + lastCands.size() + " | mob=\"" + mobFilter + "\" axe=\"" + axeName
                + "\" hole=\"" + holeName + "\" golden=" + golden;
    }

    private void releaseMovement(Minecraft mc) {
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keySprint, mc.options.keyJump,
                mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight}) {
            k.setDown(false);
        }
    }

    // ---- path following (same follower as the mushroom macro) ------------------------------------------

    private long validateAt;
    private Cursor spinCursor;
    private int spinIdx;
    private float spinAccum, spinLastYaw;

    /**
     * Looks at the next few path cells in the live block cache: if one stopped being standable (block
     * placed/broken, terrain changed) re-plan right now instead of walking into it.
     */
    private void validateLeg(LocalPlayer p, long now) {
        var g = cache.grid();
        if (g == null || leg == null || legCursor == null || now - validateAt < 150) return;
        validateAt = now;
        List<Vec3> pts = leg.path();
        for (int k = legCursor.idx; k < Math.min(pts.size(), legCursor.idx + 6); k++) {
            Vec3 v = pts.get(k);
            int x = (int) Math.floor(v.x), y = (int) Math.floor(v.y + 0.01), z = (int) Math.floor(v.z);
            if (g.get(x, y, z) != WalkCache.UNKNOWN && !g.standable(x, y, z)) {
                planDirty = true;
                lastPlanRequest = 0; // bypass the request throttle
                return;
            }
        }
    }

    private void beginLeg(Planner.Leg l, LocalPlayer p) {
        legCursor = new Cursor(l.path(), l.moves(), false);
        // continue from the path point nearest to us
        List<Vec3> pts = l.path();
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            double d = pts.get(i).distanceToSqr(p.position());
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        legCursor.idx = Math.min(best + 1, pts.size() - 1);
        arrivedAt = 0;
        legStuck = 0;
    }

    /**
     * Walks along {@code c}: pure-pursuit steering at a point ahead on the path (only if the straight
     * line there is walkable), speed-scaled arrival, turn-before-walk on sharp corners, jumps where the
     * path steps up, and a progress watchdog that backs off and reports STUCK.
     */
    private Follow follow(Minecraft mc, LocalPlayer p, Cursor c) {
        int n = c.pts.size();
        Vec3 pos = p.position();

        if (recoverTicks > 0) { // back off, slide sideways around whatever we hit, and hop
            recoverTicks--;
            boolean phase = recoverTicks > 2; // first 4 ticks
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyDown.setDown(phase);
            mc.options.keyRight.setDown(phase && strafeDir > 0);
            mc.options.keyLeft.setDown(phase && strafeDir < 0);
            mc.options.keyJump.setDown(phase && p.onGround());
            aim.setTarget(recoverYaw, 6f, WALK_TURN_SPEED, 1.0f);
            moving = true;
            return Follow.MOVING;
        }
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);

        if (n == 0 || (!c.loop && c.idx >= n)) return arrive(mc);

        Vec3 wp = c.pts.get(c.idx);
        boolean hasPrev = c.loop ? n > 1 : c.idx > 0;
        Vec3 prev = hasPrev ? c.pts.get((c.idx - 1 + n) % n) : pos;

        double speed = Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z); // blocks per tick
        boolean last = !c.loop && c.idx == n - 1;
        double arrive = last ? Math.max(0.6, speed * 2.5) : Math.max(ARRIVE_DIST, speed * 4);
        Planner.Move mv = c.moveAt(c.idx);
        boolean gap = mv == Planner.Move.GAP;
        // planner paths tell us exactly where the jumps are; the patrol route falls back to heights
        boolean stepUp = mv == Planner.Move.STEP_UP || gap || (mv == null && wp.y - p.getY() >= 0.6);
        // Advance past every node we have reached or already flown past. At 400 speed we cover ~1 block
        // per tick, so on stairs we can skip right over a step node; if that node stayed our target it
        // ended up behind us, we turned around for it, overshot again and ran in circles.
        for (int guard = 0; guard < 6; guard++) {
            double hd0 = hDist(pos, wp);
            boolean passed = hasPrev && segmentT(prev, wp, pos) >= 1.0;
            boolean reached;
            if (stepUp) {
                // the real surface can be up to half a block below the cell height (slab, stair low half)
                boolean climbed = hd0 < 0.9 && p.getY() >= wp.y - 0.6;
                boolean onNode = hd0 < 0.4 && p.onGround();
                boolean overIt = passed && p.getY() >= wp.y - 0.6; // flew over it, on top of the step
                reached = climbed || onNode || overIt;
                if (passed && !reached && p.getY() < wp.y - 1.1 && p.onGround()) {
                    // beside/past the step but a whole block too low: we are not on this staircase
                    planDirty = true;
                    lastPlanRequest = 0;
                }
            } else {
                reached = hd0 < arrive || passed;
            }
            if (!reached) break;
            c.idx++;
            if (!c.loop && c.idx >= n) return arrive(mc);
            c.idx %= n;
            wp = c.pts.get(c.idx);
            hasPrev = c.loop ? n > 1 : c.idx > 0;
            prev = hasPrev ? c.pts.get((c.idx - 1 + n) % n) : pos;
            last = !c.loop && c.idx == n - 1;
            mv = c.moveAt(c.idx);
            gap = mv == Planner.Move.GAP;
            stepUp = mv == Planner.Move.STEP_UP || gap || (mv == null && wp.y - p.getY() >= 0.6);
            arrive = last ? Math.max(0.6, speed * 2.5) : Math.max(ARRIVE_DIST, speed * 4);
        }
        double toWp = hDist(pos, wp);

        // steer: carrot ahead on the path, but face a step squarely and never aim through walls
        // look-ahead grows with speed (~10 ticks of travel): a short one makes the camera overcorrect
        // and weave left/right when moving fast
        double look = Math.max(LOOKAHEAD, 2.0 + speed * 10.0);
        Vec3 carrot = hasPrev ? carrot(c, prev, pos, look) : wp;
        if (stepUp && toWp < 2.5) carrot = wp;
        else if (!c.loop && !Planner.walkable(cache.grid(), pos, carrot)) carrot = wp;
        // Standing on a node (stairs, slabs): the direction to a point right under us flips wildly and
        // the camera spins. Aim at the next node instead, or along the path, or just hold the heading.
        boolean holdYaw = false;
        if (toWp < 0.8) {
            Vec3 nxt = (c.loop || c.idx + 1 < n) ? c.pts.get((c.idx + 1) % n) : null;
            if (nxt != null && hDist(wp, nxt) > 0.3) {
                carrot = nxt;
            } else if (hasPrev && hDist(prev, wp) > 0.3) {
                double len = hDist(prev, wp);
                carrot = new Vec3(wp.x + (wp.x - prev.x) / len * 1.5, wp.y, wp.z + (wp.z - prev.z) / len * 1.5);
            } else {
                holdYaw = true;
            }
        }
        float[] ang = anglesTo(p, new Vec3(carrot.x, p.getEyeY(), carrot.z));
        float yawErr = holdYaw ? 0f : net.minecraft.util.Mth.wrapDegrees(ang[0] - p.getYRot());
        // dead zone: ignore tiny heading errors so it doesn't hunt left/right on a straight line
        float targetYaw = holdYaw || (Math.abs(yawErr) < 2.0f && !stepUp) ? p.getYRot() : ang[0];
        aim.setTarget(targetYaw, 6f, WALK_TURN_SPEED, 1.0f); // a little downward, like a person walking

        float headingErr = Math.abs(yawErr);
        boolean coast = last && toWp < speed * 3 + 0.3; // let it roll to a stop on the stand spot
        boolean careful = (last && toWp < 2.5) || (stepUp && !gap && toWp < Math.max(2.5, speed * 8));
        // ledges: no sprint and pulse the forward key (2 of 3 ticks) so it creeps instead of sliding off
        boolean edgy = nearEdge(p);
        boolean fwd = headingErr < 75f && !coast;
        if (edgy && ++pulse % 3 == 0) fwd = false;
        mc.options.keyUp.setDown(fwd);
        mc.options.keySprint.setDown(headingErr < 35f && !careful && !edgy);

        // jump where the path climbs, or when a block in our way really needs climbing
        boolean jump = false;
        if (p.onGround()) {
            if (gap) jump = toWp < 2.3 + speed; // sprint-jump off the lip of a one block gap
            else if (stepUp && toWp < Math.max(1.3, speed * 3) && wp.y - p.getY() >= 0.55) jump = true;
            else if (p.horizontalCollision) {
                double dx, dz;
                if (speed > 0.05) {
                    dx = p.getDeltaMovement().x / speed;
                    dz = p.getDeltaMovement().z / speed;
                } else {
                    double d = Math.max(1e-6, toWp);
                    dx = (wp.x - pos.x) / d;
                    dz = (wp.z - pos.z) / d;
                }
                int ob = obstacle(mc, p, dx, dz);
                jump = ob == 1;
                if (ob == 2) { // fence / wall / gate: can't be jumped, re-plan around it right now
                    long nowMs = System.currentTimeMillis();
                    avoid.put(BlockPos.containing(wp.x, wp.y, wp.z), nowMs);
                    planDirty = true;
                    lastPlanRequest = 0;
                }
            }
        }
        mc.options.keyJump.setDown(jump);

        // anti-spin: more than 1.5 turns of camera rotation without reaching the node = something is wrong
        // with this node; avoid it and re-plan instead of circling forever
        float yawNow = p.getYRot();
        if (c != spinCursor || c.idx != spinIdx) {
            spinCursor = c;
            spinIdx = c.idx;
            spinAccum = 0f;
        } else {
            spinAccum += Math.abs(net.minecraft.util.Mth.wrapDegrees(yawNow - spinLastYaw));
            if (spinAccum > 540f) {
                spinAccum = 0f;
                long nowMs = System.currentTimeMillis();
                avoid.put(BlockPos.containing(wp.x, wp.y, wp.z), nowMs);
                avoid.put(p.blockPosition(), nowMs);
                planDirty = true;
                lastPlanRequest = 0;
                legStuck++;
                if (leg != null && c == legCursor && legStuck >= 3) { // keeps happening: give up on this group
                    for (BlockPos t : leg.targets()) blacklist.put(t, nowMs);
                    leg = null;
                }
            }
        }
        spinLastYaw = yawNow;

        // progress watchdog: must get 0.3 blocks closer to the current point within 25 ticks
        if (c != wdCursor || c.idx != wdIdx) {
            wdCursor = c;
            wdIdx = c.idx;
            wdBest = toWp;
            wdTicks = 0;
        } else if (toWp < wdBest - 0.3 || toWp < 0.4) {
            wdBest = Math.min(wdBest, toWp);
            wdTicks = 0;
        } else if (headingErr < 75f && ++wdTicks > 25) {
            wdTicks = 0;
            wdCursor = null;
            long now = System.currentTimeMillis();
            BlockPos here = p.blockPosition();
            BlockPos there = BlockPos.containing(wp.x, wp.y, wp.z);
            for (int ax = -1; ax <= 1; ax++)
                for (int az = -1; az <= 1; az++) {
                    avoid.put(here.offset(ax, 0, az), now);
                }
            avoid.put(there, now);
            strafeDir = pickStrafe(p);
            recoverYaw = p.getYRot() + strafeDir * 20f;
            recoverTicks = 6;
            moving = true;
            return Follow.STUCK;
        }
        moving = true;
        return Follow.MOVING;
    }

    private Follow arrive(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyDown.setDown(false);
        wdCursor = null;
        return Follow.ARRIVED;
    }

    /** Point {@code lookahead} blocks further along the points from our projection onto the current segment. */
    private static Vec3 carrot(Cursor c, Vec3 prev, Vec3 pos, double lookahead) {
        int n = c.pts.size();
        Vec3 a = prev, b = c.pts.get(c.idx);
        double t = Math.max(0, Math.min(1, segmentT(a, b, pos)));
        Vec3 cur = a.add(b.subtract(a).scale(t));
        double left = lookahead;
        int idx = c.idx;
        for (int k = 0; k < n; k++) {
            Vec3 target = c.pts.get(idx);
            double d = hDist(cur, target);
            if (d >= left) return cur.add(target.subtract(cur).scale(left / d));
            left -= d;
            cur = target;
            if (!c.loop && idx == n - 1) return cur;
            idx = (idx + 1) % n;
        }
        return cur;
    }

    /** Horizontal position of {@code pos} along segment a→b: 0 at a, 1 at b. */
    private static double segmentT(Vec3 a, Vec3 b, Vec3 pos) {
        double sx = b.x - a.x, sz = b.z - a.z, len2 = sx * sx + sz * sz;
        if (len2 < 1e-6) return 1;
        return ((pos.x - a.x) * sx + (pos.z - a.z) * sz) / len2;
    }

    private static double hDist(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** Standing at, or about to step onto, a ledge. */
    private boolean nearEdge(LocalPlayer p) {
        var g = cache.grid();
        if (g == null) return false;
        BlockPos b = p.blockPosition();
        if (g.edge(b.getX(), b.getY(), b.getZ())) return true;
        double yaw = Math.toRadians(p.getYRot());
        int ax = (int) Math.floor(p.getX() - Math.sin(yaw) * 1.2);
        int az = (int) Math.floor(p.getZ() + Math.cos(yaw) * 1.2);
        return g.edge(ax, b.getY(), az);
    }

    /**
     * What stands in the walking direction at foot height: 0 = nothing, 1 = a step that a jump gets us
     * over (collision top <= 1 block and head room above), 2 = something too tall to jump (fence, wall,
     * gate: collision 1.5). Walking down a step or off a ledge never needs a jump.
     */
    private int obstacle(Minecraft mc, LocalPlayer p, double dx, double dz) {
        for (double dist : new double[]{0.5, 0.9}) {
            BlockPos feet = BlockPos.containing(p.getX() + dx * dist, p.getY() + 0.1, p.getZ() + dz * dist);
            var shape = mc.level.getBlockState(feet).getCollisionShape(mc.level, feet);
            if (shape.isEmpty()) continue;
            if (shape.max(net.minecraft.core.Direction.Axis.Y) > 1.01) {
                avoid.put(feet, System.currentTimeMillis());
                return 2;
            }
            if (!isSolid(mc, feet.above()) && !isSolid(mc, feet.above(2))
                    && !isSolid(mc, p.blockPosition().above(2))) return 1;
        }
        return 0;
    }

    /** Which way (1 = right, -1 = left) there is more room to slide around an obstacle. */
    private int pickStrafe(LocalPlayer p) {
        var g = cache.grid();
        double yaw = Math.toRadians(p.getYRot());
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        if (g != null) {
            int y = p.blockPosition().getY();
            boolean r = g.standable((int) Math.floor(p.getX() + rx), y, (int) Math.floor(p.getZ() + rz));
            boolean l = g.standable((int) Math.floor(p.getX() - rx), y, (int) Math.floor(p.getZ() - rz));
            if (r && !l) return 1;
            if (l && !r) return -1;
        }
        return rand.nextBoolean() ? 1 : -1;
    }

    private static boolean isSolid(Minecraft mc, BlockPos pos) {
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    private static float[] anglesTo(LocalPlayer p, Vec3 to) {
        Vec3 eyes = p.getEyePosition();
        double dx = to.x - eyes.x, dy = to.y - eyes.y, dz = to.z - eyes.z;
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
        return new float[]{yaw, pitch};
    }

    private void releaseKeys(Minecraft mc) {
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keySprint,
                mc.options.keyJump, mc.options.keyAttack, mc.options.keyDown,
                mc.options.keyLeft, mc.options.keyRight}) {
            k.setDown(false);
        }
    }
}
