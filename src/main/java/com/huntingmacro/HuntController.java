package com.huntingmacro;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.huntingmacro.pathing.Planner;
import com.huntingmacro.pathing.WalkCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
    private static final long STUCK_MS = 8000;
    private static final double STUCK_RADIUS = 3.0;

    private enum Phase { HUNT, HIT, GOLDEN, HOLE }

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
    public volatile String mobFilter = "Miner";
    public volatile String axeName = "Prime Huntaxe";
    public volatile String holeName = "Medium Pocket Black Hole";
    public volatile Golden golden = Golden.OFF;
    public volatile double holeRadius = 7.0;
    public volatile int holeSeconds = 10;
    public volatile double range = 40.0;
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
    /** Where the zombie stood when we hit it: the black hole goes down here. */
    private Vec3 holeAt;

    /** entity id -> time we hit it (never hit twice) or gave up on it. */
    private final Map<Integer, Long> tagged = new HashMap<>();
    private record Hole(Vec3 pos, long until) {}
    private final List<Hole> holes = new ArrayList<>();

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

    public HuntController(WalkCache cache) {
        this.cache = cache;
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
        if (findByName(mc.player, holeName) < 0) {
            Chat.msg("Warning: no item named \"" + holeName + "\" in your hotbar; the black hole step will be skipped (/hunt hole <text>).");
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
        holes.clear();
        recoverTicks = 0;
        wdCursor = null;
        anchor = null;
        warpPauseUntil = 0;
        idleSince = 0;
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
        if (!running) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.screen != null) {
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
        tagged.values().removeIf(t -> now - t > TAG_MS);
        holes.removeIf(h -> now > h.until);
        avoid.values().removeIf(t -> now - t > AVOID_MS);
        blacklist.values().removeIf(t -> now - t > BLACKLIST_MS);

        moving = false;
        switch (phase) {
            case HUNT -> hunt(mc, p, now);
            case HIT -> hit(mc, p, now);
            case GOLDEN -> goldenStep(mc, p, now);
            case HOLE -> holeStep(mc, p, now);
        }
        if (phase == Phase.HUNT) stuckCheck(mc, p, now);
    }

    private void setPhase(Phase ph, long now) {
        phase = ph;
        phaseSince = now;
        stepTicks = 0;
    }

    // ---- finding zombies -----------------------------------------------------------------------

    /** Untouched filter-matching zombies in range that are not standing in a black hole, nearest first. */
    private List<Entity> candidates(Minecraft mc, LocalPlayer p, long now) {
        AABB box = p.getBoundingBox().inflate(range, Math.min(range, 20), range);
        List<Entity> all = mc.level.getEntities((Entity) null, box);
        String filter = mobFilter.toLowerCase(Locale.ROOT);
        List<Entity> out = new ArrayList<>();
        for (Entity e : all) {
            if (e.getType() != EntityType.ZOMBIE || !e.isAlive() || tagged.containsKey(e.getId())) continue;
            if (inHole(e.position())) continue;
            if (!filter.isEmpty() && !nameMatches(e, all, filter)) continue;
            out.add(e);
        }
        Vec3 me = p.position();
        out.sort((a, b) -> Double.compare(a.distanceToSqr(me), b.distanceToSqr(me)));
        return out;
    }

    private boolean inHole(Vec3 pos) {
        for (Hole h : holes) if (h.pos.distanceTo(pos) <= holeRadius) return true;
        return false;
    }

    /** SkyBlock mobs carry their name on a floating armor stand just above them (or on the mob itself). */
    private static boolean nameMatches(Entity zombie, List<Entity> all, String filter) {
        var own = zombie.getCustomName();
        if (own != null && own.getString().toLowerCase(Locale.ROOT).contains(filter)) return true;
        AABB near = zombie.getBoundingBox().inflate(0.9, 2.6, 0.9);
        for (Entity e : all) {
            if (e.getType() != EntityType.ARMOR_STAND || !near.contains(e.position())) continue;
            var n = e.getCustomName();
            if (n != null && n.getString().toLowerCase(Locale.ROOT).contains(filter)) return true;
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
                        + (n != null ? " name=" + n.getString() : ""));
            } else if (e.getType() == EntityType.ARMOR_STAND && e.getCustomName() != null) {
                Chat.msg("tag: " + e.getCustomName().getString());
            }
        }
        Chat.msg(z + " zombies within 30 blocks. Mob filter is \"" + mobFilter + "\" (/hunt mob <text>).");
    }

    // ---- HUNT: walk to the next zombie -----------------------------------------------------------

    private void hunt(Minecraft mc, LocalPlayer p, long now) {
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
            return;
        }
        idleSince = 0;

        // close enough with a clear line: stop and hit it
        Entity near = cands.get(0);
        if (inMelee(mc, p, near)) {
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
            holeAt = current.position();
            hits++;
            wait = 1 + rand.nextInt(2); // place the black hole right away
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
            wait = 1 + rand.nextInt(3);
            setPhase(Phase.HOLE, now);
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
            default -> {
                wait = 1 + rand.nextInt(3);
                setPhase(Phase.HOLE, now);
            }
        }
    }

    // ---- HOLE: look down, place the black hole -----------------------------------------------------------

    private void holeStep(Minecraft mc, LocalPlayer p, long now) {
        releaseMovement(mc);
        int slot = findByName(p, holeName);
        if (slot < 0) { // nothing to place: carry on hunting
            finishCycle(p, now);
            return;
        }
        if (wait-- > 0) return;
        if (p.getInventory().getSelectedSlot() != slot) {
            p.getInventory().setSelectedSlot(slot);
            wait = 3 + rand.nextInt(4);
            return;
        }
        // look at the ground a step in front of us
        aim.setTarget(p.getYRot(), 70f, 500f, 1f);
        boolean onGround = mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK;
        if (aim.settled(p, 4f) && onGround) {
            BlockHitResult bhr = (BlockHitResult) mc.hitResult;
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
            p.swing(InteractionHand.MAIN_HAND);
            holes.add(new Hole(holeAt != null ? holeAt : p.position(), now + holeSeconds * 1000L));
            holesPlaced++;
            wait = 4 + rand.nextInt(4);
            finishCycle(p, now);
        } else if (now - phaseSince > 1500) { // can't aim at the ground: just use the item
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            holes.add(new Hole(holeAt != null ? holeAt : p.position(), now + holeSeconds * 1000L));
            holesPlaced++;
            finishCycle(p, now);
        }
    }

    /** Back to the prime axe and on to the next zombie. */
    private void finishCycle(LocalPlayer p, long now) {
        int axe = findByName(p, axeName);
        if (axe >= 0 && p.getInventory().getSelectedSlot() != axe) p.getInventory().setSelectedSlot(axe);
        current = null;
        planDirty = true;
        lastPlanRequest = 0;
        leg = null;
        legCursor = null;
        setPhase(Phase.HUNT, now);
    }

    // ---- items --------------------------------------------------------------------------------------------

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
        Planner.Plan plan = planner.poll();
        if (plan != null) accept(plan, p);

        long interval = leg == null ? 400 : 600; // zombies move: keep the goal fresh
        if ((planDirty || now - lastPlanRequest > interval) && now - lastPlanRequest > 250) requestPlan(p, now, cands);

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

    private void requestPlan(LocalPlayer p, long now, List<Entity> cands) {
        List<BlockPos> ts = new ArrayList<>();
        for (Entity e : cands) {
            BlockPos bp = BlockPos.containing(e.getX(), e.getY(), e.getZ());
            if (!blacklist.containsKey(bp)) ts.add(bp);
        }
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

    public List<Vec3> holeCentres() {
        List<Vec3> out = new ArrayList<>();
        for (Hole h : holes) out.add(h.pos);
        return out;
    }

    public String status() {
        return (running ? "RUNNING " + phase : "stopped") + " | hits=" + hits + " holes=" + holesPlaced
                + " | zombies in view=" + lastCands.size() + " | mob=\"" + mobFilter + "\" axe=\"" + axeName
                + "\" hole=\"" + holeName + "\" golden=" + golden + " holeRadius=" + holeRadius
                + " holeSeconds=" + holeSeconds;
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
