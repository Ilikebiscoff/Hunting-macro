package com.nablzz.addons.mudworm;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nablzz.addons.Feature;
import com.nablzz.addons.Features;
import com.nablzz.addons.NablzzAddons;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.Notify;
import com.nablzz.addons.minerzombie.HumanAim;
import com.nablzz.addons.minerzombie.pathing.Planner;
import com.nablzz.addons.minerzombie.pathing.WalkCache;
import com.nablzz.addons.mushroom.Route;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Galatea mudworm macro: ESP on the green sparkle spots in the ground, an auto route to the nearest one (the
 * pathfinder for walking, the Aspect of the Void's shift + right-click teleport for long hops), then one use of
 * your tool on the spot. With no spot in sight it patrols the waypoints you recorded.
 * <p>
 * What exactly you do on a spot is a setting (right-click the block / right-click the air / break the block with a
 * chosen item), because it was not known when this was written.
 */
public class MudwormMacro implements Feature {
    private static final double ACT_REACH = 4.2;
    private static final long VISITED_MS = 45_000;

    // ---- settings ----
    public volatile boolean esp = true;
    public volatile boolean useAotv = true;
    public volatile String aotvName = "Aspect of the Void";
    public volatile int etherRange = 57;
    public volatile int aotvMinDist = 20;
    public volatile boolean sprint = true;
    public volatile boolean debug = true;
    public volatile boolean hud = true;
    /** 0 = nothing, 1 = swap lobby (/lobby, /skyblock, warp back) when no spot shows up for a while. */
    public volatile int noSpotsAction = 1;
    public volatile int noSpotsSec = 20;
    public volatile String warpCommand = "warp galatea";
    public volatile String hubCommand = "hub";
    /** 0 = right-click the block, 1 = right-click in the air, 2 = break the block. */
    public volatile int action = 2;
    /** Hotbar item for the action (part of its name); empty = whatever is in your hand. */
    public volatile String toolName = "Aspect of the Void";
    public volatile int actDelayMs = 500;
    public volatile boolean grassOnly = true;
    /** Follow the route; only detour to spots within {@link #detourRadius} blocks of where we are. */
    public volatile boolean softRoute = true;
    public volatile int detourRadius = 30;
    public volatile String particleIds = "minecraft:happy_villager";

    // ---- state ----
    private volatile boolean running;
    private final WalkCache cache = new WalkCache();
    private final Planner planner = new Planner();
    private final HumanAim aim = new HumanAim();
    private Route route;
    private int waypoint;
    private boolean afterSpot, lapDone;
    private int reached, seenResets, resetsAtWarp;
    private long roundStart;
    private volatile int rounds;
    private long runMs, lastTick;
    private String stage = "Stopped";

    private BlockPos goal;
    private boolean goalIsSpot;
    private final Map<BlockPos, Long> visited = new HashMap<>();
    private final Set<BlockPos> avoid = new HashSet<>();
    private static final Learned LEARNED = new Learned();
    private Planner.Leg leg;
    private int idx;
    private long lastPlan, goalSince, nextAction, stuckAt;
    private Vec3 stuckFrom;

    // etherwarp state machine
    private int etherStep;
    private BlockPos etherBlock;
    private long etherAt, nextEther, aotvOffUntil;
    private Vec3 etherFrom, etherAim;
    private int etherFails, swapTicks;
    private final RouteTeleport routeTp = new RouteTeleport();
    // path finder v4 (see Nav4): the running search, the teleport it asked for, and the wall bookkeeping
    private Nav4.Search nav;
    private BlockPos navGoal, pendingEther;
    private long pendingSince, navNoEtherUntil, lastWallReplan, navFailAt;
    private BlockPos navFailGoal;
    private int wallStrikes;

    // lobby swap
    private int lobbyStep;
    private long lobbyAt, noSpotsSince, noManaUntil;
    private volatile int swaps;

    // stats
    private volatile int spotsDone, hops, mudwormMsgs;
    private volatile String lastMsg = "-";

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablzz_mudworm.properties");
    }

    boolean hudOn() {
        return hud;
    }

    long runMs() {
        return runMs;
    }

    int spotsDone() {
        return spotsDone;
    }

    String clock() {
        return clock(runMs);
    }

    // ---- Feature ------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return "mudworm";
    }

    @Override
    public String name() {
        return "Galatea Mudworm";
    }

    @Override
    public void init() {
        route = new Route(FabricLoader.getInstance().getConfigDir().resolve("nablzz_mudworm_route.json"));
        load();
        LEARNED.load(FabricLoader.getInstance().getConfigDir().resolve("nablz_mudworm_learned2.txt"));
        KeyMapping waypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nablzzaddons.mudworm_waypoint",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, KeyMapping.Category.MISC));
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (waypointKey.consumeClick()) {
                if (mc.player == null || mc.screen != null) continue;
                route.add(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                Notify.msg("Waypoint " + route.size() + " saved.");
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nablzzaddons", "mudworm_profit"),
                (graphics, delta) -> MudwormHud.render(graphics, delta, this));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String s = message.getString();
            if (s.toLowerCase(Locale.ROOT).contains("enough mana")) noManaUntil = System.currentTimeMillis() + 4000;
            if (running && s.contains("There are blocks in the way")) { // the server refused a plain teleport
                var pl = Minecraft.getInstance().player;
                routeTp.serverBlocked(System.currentTimeMillis(), pl == null ? null : pl.blockPosition(), goal, LEARNED);
            }
            if (overlay) return;
            if (running) MudwormProfit.onChat(s);
            if (s.contains("Mudworm")) {
                mudwormMsgs++;
                lastMsg = s.replaceAll("§.", "").trim();
            }
        });
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            if (running && mc.player != null && mc.screen == null) aim.frame(mc, mc.player);
            if (esp) drawEsp();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = LiteralArgumentBuilder.<FabricClientCommandSource>literal("mudworm");
            root.executes(c -> {
                NablzzAddons.openGui(id());
                return 1;
            });
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start").executes(c -> {
                start(Minecraft.getInstance());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop").executes(c -> {
                stop(Minecraft.getInstance());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("scan").executes(c -> {
                if (!MudwormTracker.scanning) {
                    MudwormTracker.scanning = true;
                    MudwormTracker.drainSeen();
                    Notify.msg("Counting particles. Stand next to a spot, then run /mudworm scan again.");
                } else {
                    MudwormTracker.scanning = false;
                    MudwormTracker.drainSeen().forEach((k, v) -> Notify.msg(k + ": " + v));
                    Notify.msg("Set the marker in the settings (Action tab, Marker particles).");
                }
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String status() {
        return running ? stage : "Stopped";
    }

    @Override
    public void start(Minecraft mc) {
        if (mc.player == null) return;
        Features.starting(id(), mc);
        save();
        applyMarkers();
        goal = null;
        leg = null;
        etherStep = 0;
        nextEther = 0;
        etherFails = 0;
        lobbyStep = 0;
        noSpotsSince = 0;
        seenResets = MudwormTracker.resets;
        newRound(System.currentTimeMillis());
        visited.clear();
        avoid.clear();
        aim.reset();
        running = true;
        stage = "Searching";
        Notify.msg("Mudworm macro started" + (useAotv && findSlot(mc.player, aotvName) < 0
                ? " (no \"" + aotvName + "\" in the hotbar: walking only)." : "."));
    }

    /** A fresh lobby (or a new start): forget what was done, start the route again from the closest waypoint. */
    private void newRound(long now) {
        visited.clear();
        avoid.clear();
        goal = null;
        leg = null;
        reached = 0;
        lapDone = false;
        afterSpot = true; // pick the closest waypoint first
        roundStart = now;
        noSpotsSince = 0;
        rounds++;
    }

    @Override
    public void stop(Minecraft mc) {
        if (!running) return;
        running = false;
        lobbyStep = 0;
        stage = "Stopped";
        releaseKeys(mc);
        LEARNED.save(true);
        Notify.msg("Mudworm macro stopped: " + spotsDone + " spots done, " + hops + " teleports.");
    }

    @Override
    public void report(java.util.function.Consumer<String> out) {
        long now = System.currentTimeMillis();
        out.accept("stage: " + stage + ", round " + rounds + ", lobby swaps " + swaps + ", spots done " + spotsDone + ", teleports " + hops);
        out.accept("goal: " + (goal == null ? "none" : goal.toShortString() + (goalIsSpot ? " (spot)" : " (waypoint " + waypoint + ")"))
                + ", route waypoints " + (route == null ? 0 : route.size()) + ", reached " + reached + ", lap done " + lapDone);
        out.accept("spots known: " + MudwormTracker.SPOTS.size() + ", lobby/world resets " + MudwormTracker.resets);
        int n = 0;
        StringBuilder sp = new StringBuilder();
        for (BlockPos b : MudwormTracker.SPOTS.keySet()) {
            if (n++ >= 12) break;
            sp.append(b.toShortString()).append("; ");
        }
        if (n > 0) out.accept("first spots: " + sp);
        out.accept("learned: " + LEARNED.summary());
        out.accept("teleport state: step " + etherStep + ", fails in a row " + etherFails
                + ", no-mana for " + Math.max(0, (noManaUntil - now) / 1000) + " s, paused for " + Math.max(0, (aotvOffUntil - now) / 1000)
                + " s, route right-click paused for " + Math.max(0, (routeTp.offUntilMs() - now) / 1000) + " s");
        out.accept("last teleport problem: " + lastEtherFail);
        out.accept("settings: aotv " + useAotv + " \"" + aotvName + "\", shift-teleport beyond " + aotvMinDist + " blocks, range " + etherRange
                + ", right-click route " + routeTp.enabled + " every " + routeTp.delayMs + " ms, sprint " + sprint
                + ", action " + action + " with \"" + toolName + "\", soft route " + softRoute + " radius " + detourRadius
                + ", no-spots action " + noSpotsAction + " after " + noSpotsSec + " s, grass only " + grassOnly + ", markers " + particleIds);
        out.accept("profit: " + String.format(Locale.ROOT, "%.0f coins, %d items", MudwormProfit.coins(), MudwormProfit.items()));
    }

    @Override
    public void saveSettings() {
        save();
        applyMarkers();
    }

    private void applyMarkers() {
        MudwormTracker.grassOnly = grassOnly;
        Set<String> set = new HashSet<>();
        for (String id : particleIds.split("[\\s,]+")) {
            if (id.isBlank()) continue;
            set.add(id.contains(":") ? id : "minecraft:" + id);
        }
        if (!set.isEmpty()) MudwormTracker.markers = set;
    }

    // ---- the loop -----------------------------------------------------------------------------------------

    private void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (running && lastTick != 0) runMs += Math.min(1000, now - lastTick);
        lastTick = now;
        if (running || MudwormProfit.items() > 0) MudwormProfit.tickPrices(now);
        MudwormTracker.enabled = esp || running;
        if (MudwormTracker.enabled || MudwormTracker.scanning) MudwormTracker.tick(mc);
        LEARNED.save(false);
        if (!running) return;
        if (lobbyStep != 0) { // swapping lobby: the player may be gone for a while
            lobbyTick(mc, now);
            return;
        }
        if (MudwormTracker.resets != seenResets) { // joined a new lobby / world: do the route again
            seenResets = MudwormTracker.resets;
            newRound(now);
            Notify.msg("New lobby: starting the route again.");
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.screen != null) {
            releaseKeys(mc);
            return;
        }
        visited.values().removeIf(t -> now - t > VISITED_MS);

        if (etherStep != 0) { // a teleport is in progress: nothing else moves
            etherTick(mc, p, now);
            return;
        }

        chooseGoal(p, now);
        boolean hasRoute = route != null && route.size() > 0;
        if (hasRoute && reached >= route.size()) lapDone = true;
        // round over: the route has been walked once and no spot is left -> /hub, /warp galatea, again
        if (goal == null && noSpotsAction == 1 && now - roundStart > 8000) {
            if (hasRoute ? lapDone : MudwormTracker.SPOTS.isEmpty() && now - roundStart > noSpotsSec * 1000L) {
                startLobbySwap(mc, p, now);
                return;
            }
        }
        if (goal == null) {
            stage = lapDone ? "Round done" : "Searching";
            releaseMovement(mc);
            return;
        }

        Vec3 eye = p.getEyePosition();
        Vec3 top = topOf(goal);
        double hd = Math.hypot(top.x - p.getX(), top.z - p.getZ());

        BlockHitResult face = goalIsSpot ? rayHit(mc, p, eye, goal) : null; // any face we can see
        if (face != null && eye.distanceTo(face.getLocation()) <= ACT_REACH) {
            act(mc, p, now, face);
            return;
        }
        if (!goalIsSpot && hd < 2.5 && Math.abs(goal.getY() + 1.0 - p.getY()) < 6.0) { // reached a patrol waypoint
            waypoint++;
            reached++;
            goal = null;
            return;
        }
        // walking is the default; shift + right-click only for long hops. When there is no way on foot (a tree, a ledge,
        // a gap), walk() teleports over by itself.
        boolean detour = leg != null && hd > 12.0 && pathLength(leg) > 1.8 * hd; // the way on foot is much longer than the straight line
        if (hd > aotvMinDist || detour) {
            if (!aotvReady(mc, p, now)) {
                tpMsg(now, "wants to teleport (" + (int) hd + " blocks away) but: " + whyNotReady(p, now));
            } else if (tryEther(mc, p, now, eye, false)) {
                return;
            }
        }

        walk(mc, p, now);
    }

    /** Nearest spot not done recently; otherwise the next patrol waypoint. */
    private void chooseGoal(LocalPlayer p, long now) {
        if (goal != null && goalIsSpot && !MudwormTracker.SPOTS.containsKey(goal)) goal = null; // gone: done or expired
        if (goal != null && !goalIsSpot && nearestSpot(p) != null) goal = null; // a spot showed up: go there
        if (goal != null) {
            if (now - goalSince > 40_000) { // took too long: skip it for a while
                visited.put(goal, now);
                goal = null;
            } else {
                return;
            }
        }
        BlockPos spot = nearestSpot(p);
        leg = null;
        idx = 0;
        goalSince = now;
        if (spot != null) {
            goal = spot;
            goalIsSpot = true;
            return;
        }
        if (route != null && route.size() > 0 && !lapDone) {
            if (afterSpot) { // back from a detour: continue from the closest waypoint
                afterSpot = false;
                double bd = Double.MAX_VALUE;
                for (int i = 0; i < route.size(); i++) {
                    double d = route.get(i).distanceToSqr(p.position());
                    if (d < bd) {
                        bd = d;
                        waypoint = i;
                    }
                }
            }
            waypoint = waypoint % route.size();
            Vec3 w = route.get(waypoint);
            goal = BlockPos.containing(w.x, w.y - 0.5, w.z);
            goalIsSpot = false;
        }
    }

    private BlockPos nearestSpot(LocalPlayer p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        boolean soft = softRoute && route != null && route.size() > 0 && !lapDone;
        for (BlockPos s : MudwormTracker.SPOTS.keySet()) {
            if (visited.containsKey(s)) continue;
            double d = s.getCenter().distanceToSqr(p.position());
            if (soft && d > (double) detourRadius * detourRadius) continue; // far away: the route gets us there
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    // ---- the action on a spot -----------------------------------------------------------------------------

    private void act(Minecraft mc, LocalPlayer p, long now, BlockHitResult face) {
        stage = "Digging";
        releaseMovement(mc);
        Vec3 top = face.getLocation();
        float[] a = angles(p.getEyePosition(), top);
        aim.setTarget(a[0], a[1], 700f, 1.2f);
        if (now < nextAction || !aim.settled(p, 2.5f)) return;
        if (!toolName.isBlank()) {
            int s = findSlot(p, toolName);
            if (s >= 0 && p.getInventory().getSelectedSlot() != s) {
                p.getInventory().setSelectedSlot(s);
                nextAction = now + 150; // let the server see the slot change
                return;
            }
        }
        switch (action) {
            case 1 -> mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            case 2 -> {
                mc.gameMode.startDestroyBlock(goal, face.getDirection());
                mc.options.keyAttack.setDown(true);
            }
            default -> mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(top, face.getDirection(), goal, false));
        }
        p.swing(InteractionHand.MAIN_HAND);
        spotsDone++;
        afterSpot = true;
        visited.put(goal, now);
        goal = null;
        nextAction = now + actDelayMs;
        if (action == 2) nextAction = now + Math.max(actDelayMs, 1200);
        mc.options.keyAttack.setDown(false);
    }

    // ---- walking (pathfinder) -----------------------------------------------------------------------------

    private void walk(Minecraft mc, LocalPlayer p, long now) {
        stage = goalIsSpot ? "Walking to a spot" : "Walking the route";
        if (nav != null && !goal.equals(navGoal)) nav = null; // the goal changed while it was searching
        if (nav != null && nav.step(3_000_000L)) finishPlan(p);
        if (pendingEther != null) { // the plan starts with a teleport
            stage = "Teleporting";
            releaseMovement(mc);
            if (aotvReady(mc, p, now)) {
                BlockPos pick = pendingEther;
                pendingEther = null;
                if (!beginEther(mc, p, now, p.getEyePosition(), pick, true)) pendingEther = null;
            } else if (now - pendingSince > 3000) { // cannot teleport (cooldown / no mana): walk instead
                pendingEther = null;
                navNoEtherUntil = now + 8000;
                leg = null;
                lastPlan = 0;
            }
            return;
        }
        boolean failedRecently = goal.equals(navFailGoal) && now - navFailAt < 2500;
        if (nav == null && !failedRecently && ((leg == null && now - lastPlan > 400) || now - lastPlan > 6000)) startPlan(mc, p, now);
        Vec3 target;
        if (leg == null || leg.path().isEmpty()) {
            Vec3 c = goal.getCenter();
            {
                releaseMovement(mc);
                if (nav != null) { // still searching
                    stage = "Planning the path";
                    float[] look = angles(p.getEyePosition(), c);
                    aim.setTarget(look[0], 8f, 520f, 1f);
                    return;
                }
                if (now - goalSince > 1500) { // no way on foot: teleport over
                    if (aotvReady(mc, p, now) && tryEther(mc, p, now, p.getEyePosition(), true)) return;
                    if (routeTp(mc, p, now, p.getEyePosition(), Math.hypot(goal.getX() + 0.5 - p.getX(), goal.getZ() + 0.5 - p.getZ()))) return;
                }
                stage = "No path";
                float[] look = angles(p.getEyePosition(), c);
                aim.setTarget(look[0], 8f, 520f, 1f); // keep facing the goal instead of freezing the camera
                if (now - goalSince > 4000) { // nothing reachable: skip it for a while
                    visited.put(goal, now);
                    if (!goalIsSpot) { waypoint++; reached++; } // a waypoint we cannot reach still counts as done
                    goal = null;
                }
                return;
            }
        } else {
            List<Vec3> pts = leg.path();
            while (idx < pts.size() - 1) {
                double d0 = Math.hypot(pts.get(idx).x - p.getX(), pts.get(idx).z - p.getZ());
                double d1 = Math.hypot(pts.get(idx + 1).x - p.getX(), pts.get(idx + 1).z - p.getZ());
                boolean flat = Math.abs(pts.get(idx).y - p.getY()) < 1.5;
                if ((d0 < 0.9 && flat) || (d1 < d0 && d1 < 2.5 && Math.abs(pts.get(idx + 1).y - p.getY()) < 1.5)) idx++;
                else break;
            }
            target = pts.get(Math.min(idx + 1, pts.size() - 1));
        }
        float yaw = Math.hypot(target.x - p.getX(), target.z - p.getZ()) < 0.2 ? p.getYRot()
                : (float) (Math.atan2(target.z - p.getZ(), target.x - p.getX()) * 180.0 / Math.PI) - 90f;
        aim.setTarget(yaw, 8f, 520f, 1f);
        float err = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - p.getYRot()));
        boolean up = target.y > p.getY() + 0.6;
        boolean forward = err < 55f;
        boolean jump = p.onGround() && (p.horizontalCollision || up);
        // look at the blocks right ahead: never walk into a wall
        double tdx = target.x - p.getX(), tdz = target.z - p.getZ(), tl = Math.hypot(tdx, tdz);
        if (tl > 0.35 && err < 40f) {
            Vec3 dir = new Vec3(tdx / tl, 0, tdz / tl);
            double reach = Math.min(0.6, tl);
            Vec3 f0 = new Vec3(p.getX(), p.getY() + 0.3, p.getZ()), f1 = new Vec3(p.getX(), p.getY() + 1.55, p.getZ());
            BlockHitResult lo = mc.level.clip(new ClipContext(f0, f0.add(dir.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            BlockHitResult hi = mc.level.clip(new ClipContext(f1, f1.add(dir.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            boolean loHit = lo.getType() == HitResult.Type.BLOCK, hiHit = hi.getType() == HitResult.Type.BLOCK;
            if (loHit || hiHit) {
                BlockPos wallPos = (loHit ? lo : hi).getBlockPos();
                boolean stepOver = loHit && !hiHit && up; // a one-block step the path wants us to climb
                if (stepOver) {
                    jump = p.onGround();
                } else if (loHit && !hiHit && mc.level.getBlockState(wallPos.above()).getCollisionShape(mc.level, wallPos.above()).isEmpty()
                        && mc.level.getBlockState(wallPos.above(2)).getCollisionShape(mc.level, wallPos.above(2)).isEmpty()) {
                    jump = p.onGround(); // a low block in the way: hop over it
                } else {
                    forward = false; // a wall: stop and plan around it
                    if (now - lastWallReplan > 1500 || p.horizontalCollision && now - lastWallReplan > 700) {
                        lastWallReplan = now;
                        BlockPos cell = BlockPos.containing(p.getX() + dir.x, p.getY() + 0.1, p.getZ() + dir.z);
                        avoid.add(cell);
                        if (++wallStrikes >= 2) LEARNED.addAvoid(cell);
                        leg = null;
                        lastPlan = 0;
                        stage = "Wall ahead - planning around it";
                    }
                }
            } else if (tl > 1.0) {
                wallStrikes = 0;
            }
        }
        // a gap jump: sprint-jump from the edge of the take-off cell
        if (leg != null && idx + 1 < leg.moves().size() && leg.moves().get(idx + 1) == Planner.Move.GAP
                && Math.hypot(leg.path().get(idx).x - p.getX(), leg.path().get(idx).z - p.getZ()) < 0.75) jump = p.onGround();
        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(sprint && forward && err < 20f);
        mc.options.keyJump.setDown(jump);
        if (etherStep == 0) sneak(mc, false);

        // stuck: not 2 blocks further in 5 s
        if (stuckFrom == null || p.position().distanceTo(stuckFrom) > 2.0) {
            stuckFrom = p.position();
            stuckAt = now;
        } else if (now - stuckAt > 5000) {
            stuckAt = now;
            if (leg != null && !leg.path().isEmpty()) {
                BlockPos bad = BlockPos.containing(leg.path().get(Math.min(idx + 1, leg.path().size() - 1)));
                avoid.add(bad);
                LEARNED.addAvoid(bad); // remembered for every later lobby
            }
            leg = null;
            lastPlan = 0;
            if (now - goalSince > 15_000 && goal != null) {
                visited.put(goal, now);
                goal = null;
            }
        }
    }

    // ---- Aspect of the Void: shift + right-click --------------------------------------------------------------

    private long lastTpMsg;

    /** A line in chat about why no teleport happens, at most every 5 s. */
    private void tpMsg(long now, String text) {
        if (now - lastTpMsg < 5000) return;
        lastTpMsg = now;
        Notify.msg("Teleport: " + text);
    }

    private String whyNotReady(LocalPlayer p, long now) {
        if (!useAotv) return "\"Use the Aspect of the Void\" is off in the settings.";
        if (findSlot(p, aotvName) < 0) return "no item named \"" + aotvName + "\" in the hotbar.";
        if (now < noManaUntil) return "not enough mana.";
        if (now < aotvOffUntil) return "paused for " + (aotvOffUntil - now) / 1000 + " s after repeated failures.";
        return "cooldown after the last teleport.";
    }

    private static double pathLength(Planner.Leg leg) {
        double sum = 0;
        List<Vec3> pts = leg.path();
        for (int i = 1; i < pts.size(); i++) sum += pts.get(i).distanceTo(pts.get(i - 1));
        return sum;
    }

    private boolean aotvReady(Minecraft mc, LocalPlayer p, long now) {
        return useAotv && now >= noManaUntil && now >= nextEther && now >= aotvOffUntil && findSlot(p, aotvName) >= 0;
    }

    /** Plain AOTV right-click toward the goal for the last stretch: see {@link RouteTeleport}. False = walk. */
    private boolean routeTp(Minecraft mc, LocalPlayer p, long now, Vec3 eye, double hd) {
        if (!useAotv || now < noManaUntil) return false;
        int slot = findSlot(p, aotvName);
        if (slot < 0) return false;
        if (routeTp.step(mc, p, now, aim, goal, hd, LEARNED) != RouteTeleport.Result.TELEPORT) return false;
        stage = "Teleport (right-click)";
        releaseMovement(mc);
        sneak(mc, false);
        if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
        if (routeTp.takeClick()) hops++;
        return true;
    }

    /** How far above our feet the goal (or the next stretch of the path) is: > 1 means we cannot just walk. */
    private double rise(LocalPlayer p) {
        double r = goal == null ? 0 : goal.getY() + 1.0 - p.getY();
        if (!goalIsSpot) r = 0;
        if (leg != null) {
            List<Vec3> pts = leg.path();
            for (int i = idx; i < Math.min(pts.size(), idx + 8); i++) r = Math.max(r, pts.get(i).y - p.getY());
        }
        return r;
    }

    /** A block you can land on: solid, with two free blocks above it. */
    private static boolean landable(Minecraft mc, BlockPos b) {
        var lv = mc.level;
        return !LEARNED.badLanding(b)
                && !lv.getBlockState(b).getCollisionShape(lv, b).isEmpty()
                && lv.getBlockState(b.above()).getCollisionShape(lv, b.above()).isEmpty()
                && lv.getBlockState(b.above(2)).getCollisionShape(lv, b.above(2)).isEmpty()
                && lv.getFluidState(b.above()).isEmpty();
    }

    /**
     * Smart pick: the block next to (or at) the spot that we can see, can land on, and from which the spot is within
     * reach, closest to the spot. Falls back to the farthest landable path point in sight.
     */
    private BlockPos smartLanding(Minecraft mc, LocalPlayer p, Vec3 eye, boolean up) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        if (goalIsSpot) {
            Vec3 spotTop = topOf(goal);
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = -3; dy <= 3; dy++)
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos b = goal.offset(dx, dy, dz);
                        if (!landable(mc, b)) continue;
                        double d = eye.distanceTo(b.getCenter());
                        if (d > etherRange || d < 2.0) continue;
                        Vec3 stand = new Vec3(b.getX() + 0.5, b.getY() + 1.0 + 1.62, b.getZ() + 0.5);
                        double toSpot = stand.distanceTo(spotTop);
                        if (toSpot > ACT_REACH - 0.3 && !b.equals(goal)) continue; // must be able to dig from there
                        if (rayHit(mc, p, eye, b) == null) continue;
                        if (toSpot < bd) {
                            bd = toSpot;
                            best = b;
                        }
                    }
            if (best != null) return best;
        }
        if (leg != null) {
            List<Vec3> pts = leg.path();
            double minHop = up ? 3.0 : aotvMinDist;
            for (int i = pts.size() - 1; i > idx; i--) {
                Vec3 v = pts.get(i);
                BlockPos floor = BlockPos.containing(v.x, v.y - 0.5, v.z);
                double d = eye.distanceTo(floor.getCenter());
                if (d < minHop || d > etherRange || !landable(mc, floor)) continue;
                if (up && v.y < p.getY() + 1.0) continue; // going up: the hop must actually gain height
                if (rayHit(mc, p, eye, floor) != null) return floor;
            }
        }
        return directLanding(mc, p, eye, up ? 3.0 : Math.min(aotvMinDist, 9.0));
    }

    /**
     * Looks around: casts a fan of rays in every direction up to teleport range, and every block a ray ends on that
     * can be landed on (solid, two free blocks above) is a candidate. Picks the one that gets us closest to the goal.
     */
    private BlockPos directLanding(Minecraft mc, LocalPlayer p, Vec3 eye, double minHop) {
        if (goal == null) return null;
        Vec3 to = goal.getCenter();
        double now = eye.distanceTo(to);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        Set<BlockPos> seen = new HashSet<>();
        double reach = etherRange;
        for (int pitch = -60; pitch <= 50; pitch += 5) {
            double cp = Math.cos(Math.toRadians(pitch)), sp = -Math.sin(Math.toRadians(pitch));
            int yawStep = Math.max(4, (int) Math.round(5.0 / Math.max(0.25, cp)));
            for (int yaw = 0; yaw < 360; yaw += yawStep) {
                double yr = Math.toRadians(yaw);
                Vec3 dir = new Vec3(-Math.sin(yr) * cp, sp, Math.cos(yr) * cp);
                BlockHitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(reach)),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (h.getType() != HitResult.Type.BLOCK) continue;
                BlockPos b = h.getBlockPos();
                if (!seen.add(b) || !landable(mc, b)) continue;
                Vec3 c = b.getCenter();
                if (eye.distanceTo(c) < minHop) continue;
                double score = c.distanceTo(to);
                if (score > now - 3.0) continue; // must get us closer
                if (score < bestScore) {
                    bestScore = score;
                    best = b;
                }
            }
        }
        return best;
    }

    /** Starts a teleport sequence. False = no good teleport right now. */
    private boolean tryEther(Minecraft mc, LocalPlayer p, long now, Vec3 eye, boolean up) {
        BlockPos pick = smartLanding(mc, p, eye, up);
        if (pick == null) {
            tpMsg(now, "no block to teleport to that gets closer to the goal (looked in every direction, range " + etherRange + ").");
            nextEther = now + 1200;
            return false;
        }
        return beginEther(mc, p, now, eye, pick, up);
    }

    private boolean hitsBlock(Minecraft mc, LocalPlayer p, Vec3 eye, Vec3 t, BlockPos b) {
        Vec3 dir = t.subtract(eye).normalize();
        BlockHitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(etherRange + 2.0)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(b);
    }

    /**
     * The point of the block to look at: one that is really in sight and keeps the crosshair on the block even when the
     * camera sways a little (neighbouring rays must still hit it). Null when the block cannot be seen from here.
     */
    private Vec3 bestAim(Minecraft mc, LocalPlayer p, Vec3 eye, BlockPos b) {
        Vec3 c = b.getCenter();
        List<Vec3> pts = new ArrayList<>();
        pts.add(c);
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            pts.add(c.add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5));
            pts.add(c.add(d.getStepX() * 0.35, d.getStepY() * 0.35, d.getStepZ() * 0.35));
        }
        Vec3 best = null;
        double bestScore = -1;
        for (Vec3 t : pts) {
            if (!hitsBlock(mc, p, eye, t, b)) continue;
            Vec3 dir = t.subtract(eye).normalize();
            Vec3 side = dir.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 1e-6) side = new Vec3(1, 0, 0);
            side = side.normalize();
            Vec3 vert = side.cross(dir).normalize();
            double dist = eye.distanceTo(t);
            double score = 1;
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                if (hitsBlock(mc, p, eye, t.add(side.scale(0.25 * sgn)), b)) score++;
                if (hitsBlock(mc, p, eye, t.add(vert.scale(0.25 * sgn)), b)) score++;
            }
            score = score * 10 - t.distanceTo(c) * 5;
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    /** Starts the teleport sequence to this landing block. */
    private boolean beginEther(Minecraft mc, LocalPlayer p, long now, Vec3 eye, BlockPos pick, boolean up) {
        Vec3 aimPt = bestAim(mc, p, eye, pick);
        if (aimPt == null) { // not visible from here (we moved since it was picked): plan again from where we stand
            if (debug) Notify.msg("Teleport: " + pick.toShortString() + " is not in sight from here, planning again.");
            nextEther = now + 300;
            leg = null;
            lastPlan = 0;
            return false;
        }
        etherBlock = pick;
        etherAim = aimPt;
        if (debug) Notify.msg(String.format(Locale.ROOT, "Teleport: aiming at the block %d %d %d (%.0f blocks away)", pick.getX(), pick.getY(), pick.getZ(), eye.distanceTo(pick.getCenter())));
        etherStep = 1;
        etherAt = now;
        swapTicks = 4;
        stage = up ? "Teleporting up" : "Teleporting";
        releaseMovement(mc);
        sneak(mc, true); // shift is held through the whole teleport
        int slot = findSlot(p, aotvName);
        if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
        return true;
    }

    private void etherTick(Minecraft mc, LocalPlayer p, long now) {
        Vec3 aimPt = etherAim;
        switch (etherStep) {
            case 1 -> { // look at the block
                float[] a = angles(p.getEyePosition(), aimPt);
                aim.setTarget(a[0], a[1], 900f, 1.3f);
                if (swapTicks > 0) swapTicks--;
                boolean onIt = crosshairOn(mc, p);
                sneak(mc, true);
                if (swapTicks == 0 && aim.settled(p, 2f) && onIt) {
                    etherStep = 2;
                    etherAt = now;
                } else if (now - etherAt > 2500) {
                    if (!onIt) LEARNED.addBadLanding(etherBlock); // something is in the way of this one
                    etherFail(mc, now, onIt ? "the camera never settled on the block" : "the crosshair is not on the block (something in the way?)");
                }
            }
            case 2 -> { // sneak is registered after a couple of ticks, then the click
                sneak(mc, true);
                if (now - etherAt > 1200 && !p.isShiftKeyDown()) { // never crouched (key setting?): give up this teleport
                    etherFail(mc, now, "the player never crouched (shift not registering)");
                } else if (now - etherAt >= 130 && p.isShiftKeyDown() && !crosshairOn(mc, p)) {
                    // the camera drifted off the block while we waited for shift: keep aiming, click only when on it
                    float[] a2 = angles(p.getEyePosition(), aimPt);
                    aim.setTarget(a2[0], a2[1], 900f, 1.3f);
                    if (now - etherAt > 2500) etherFail(mc, now, "the crosshair kept drifting off the block");
                } else if (now - etherAt >= 130 && p.isShiftKeyDown()) {
                    etherFrom = p.position();
                    if (debug) Notify.msg(String.format(Locale.ROOT, "Teleport: shift + right-click toward %d %d %d", etherBlock.getX(), etherBlock.getY(), etherBlock.getZ()));
                    mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    etherStep = 3;
                    etherAt = now;
                }
            }
            default -> { // wait for the teleport
                if (p.position().distanceTo(etherFrom) > 3.0) {
                    hops++;
                    etherFails = 0;
                    sneak(mc, false);
                    etherStep = 0;
                    nextEther = now + 350;
                    leg = null; // re-plan from the new spot
                    lastPlan = 0;
                    idx = 0;
                } else if (now - etherAt > 900) {
                    LEARNED.addBadLanding(etherBlock);
                    etherFail(mc, now, "shift + right-click sent but you did not move (no Ether Transmission upgrade, or too far/blocked?)");
                }
            }
        }
    }

    private volatile String lastEtherFail = "none";

    /** Whether the crosshair is on the teleport block (at teleport range; mc.hitResult only reaches the normal ~4.5 blocks). */
    private boolean crosshairOn(Minecraft mc, LocalPlayer p) {
        Vec3 e = p.getEyePosition();
        BlockHitResult h = mc.level.clip(new ClipContext(e, e.add(p.getLookAngle().scale(etherRange + 2.0)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(etherBlock);
    }

    private void etherFail(Minecraft mc, long now, String why) {
        lastEtherFail = why;
        Notify.msg("Teleport failed: " + why);
        sneak(mc, false);
        etherStep = 0;
        nextEther = now + 1500;
        if (++etherFails >= 3) {
            etherFails = 0;
            aotvOffUntil = now + 30_000;
            Notify.msg("The teleport keeps failing (Ether Transmission needs the upgrade, and a clear view of the block) - walking for 30 s.");
        }
    }

    // ---- lobby swap: /lobby, /skyblock, warp back ----------------------------------------------------------------

    private void startLobbySwap(Minecraft mc, LocalPlayer p, long now) {
        releaseKeys(mc);
        goal = null;
        leg = null;
        swaps++;
        Notify.msg("Round done (" + spotsDone + " spots so far) - /" + hubCommand + ", then /" + warpCommand + ".");
        if (!hubCommand.isBlank()) p.connection.sendCommand(hubCommand.trim());
        lobbyStep = 1;
        lobbyAt = now;
        stage = "Swapping lobby";
    }

    private void lobbyTick(Minecraft mc, long now) {
        stage = "Swapping lobby";
        if (mc.player == null || mc.player.connection == null) return;
        switch (lobbyStep) {
            case 1 -> { // in the hub now: warp back
                if (now - lobbyAt > 6000) {
                    resetsAtWarp = MudwormTracker.resets;
                    if (!warpCommand.isBlank()) mc.player.connection.sendCommand(warpCommand.trim());
                    lobbyStep = 2;
                    lobbyAt = now;
                }
            }
            default -> { // wait until the island world has loaded (the ESP reset marks it)
                boolean arrived = MudwormTracker.resets != resetsAtWarp && now - lobbyAt > 3000;
                if (arrived || now - lobbyAt > 25_000) {
                    lobbyStep = 0;
                    seenResets = MudwormTracker.resets;
                    newRound(now);
                    stage = "Searching";
                }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** Points on a block to try looking at: top face first, then the sides and the bottom. */
    private static Vec3[] facePoints(BlockPos b) {
        double x = b.getX(), y = b.getY(), z = b.getZ();
        return new Vec3[]{
                new Vec3(x + 0.5, y + 0.95, z + 0.5), new Vec3(x + 0.25, y + 0.95, z + 0.25), new Vec3(x + 0.75, y + 0.95, z + 0.75),
                new Vec3(x + 0.5, y + 0.5, z + 0.05), new Vec3(x + 0.5, y + 0.5, z + 0.95),
                new Vec3(x + 0.05, y + 0.5, z + 0.5), new Vec3(x + 0.95, y + 0.5, z + 0.5),
                new Vec3(x + 0.5, y + 0.05, z + 0.5)};
    }

    /** The first point of the block that a ray from the eyes reaches without hitting something else, or null. */
    private static BlockHitResult rayHit(Minecraft mc, LocalPlayer p, Vec3 eye, BlockPos b) {
        for (Vec3 to : facePoints(b)) {
            BlockHitResult h = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(b)) return h;
        }
        return null;
    }

    private static Vec3 topOf(BlockPos b) {
        return new Vec3(b.getX() + 0.5, b.getY() + 1.0, b.getZ() + 0.5);
    }

    private static float[] angles(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) -(Math.atan2(dy, Math.hypot(dx, dz)) * 180.0 / Math.PI);
        return new float[]{yaw, pitch};
    }

    private static int findSlot(LocalPlayer p, String name) {
        String t = name.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) return -1;
        for (int i = 0; i < 9; i++) {
            var st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.getHoverName().getString().toLowerCase(Locale.ROOT).contains(t)) return i;
        }
        return -1;
    }

    /**
     * Sneak on/off. A plain key is just set; with "toggle sneak" on, the key flips its state each time it is pressed,
     * so press it only when the state is not what we want.
     */
    private static void sneak(Minecraft mc, boolean want) {
        KeyMapping k = mc.options.keyShift;
        k.setDown(want);
        if (k.isDown() != want) k.setDown(true); // toggle mode: a press flips it
    }

    private Set<BlockPos> learnedAvoid() {
        Set<BlockPos> all = LEARNED.avoid();
        all.addAll(avoid);
        return all;
    }

    // ---- path finder v4 glue ---------------------------------------------------------------------------------

    /** Starts a search from where we stand to the goal (time-sliced over the next ticks, see {@link Nav4}). */
    private void startPlan(Minecraft mc, LocalPlayer p, long now) {
        Nav4World w = new Nav4World(mc.level);
        int sx = p.getBlockX(), sz = p.getBlockZ(), sy = (int) Math.floor(p.getY() + 0.05);
        Nav4.Search probe = new Nav4.Search(w, sx, sy, sz, (x, y, z) -> true, sx, sy, sz, 0, null, null, 0, 1);
        for (int dy : new int[]{0, -1, -2, 1}) { // snap to the nearest cell that can be stood on
            if (probe.standable(sx, sy + dy, sz)) {
                sy += dy;
                break;
            }
        }
        final BlockPos g = goal;
        final boolean spot = goalIsSpot;
        final Vec3 top = topOf(g);
        Nav4.Goal done;
        int tx = g.getX(), ty = g.getY() + 1, tz = g.getZ();
        double slack;
        if (spot) {
            slack = ACT_REACH - 1.0;
            done = (x, y, z) -> {
                Vec3 e = new Vec3(x + 0.5, y + 1.62, z + 0.5);
                if (e.distanceTo(top) > ACT_REACH - 0.4) return false;
                return rayHit(mc, p, e, g) != null; // and we must see it from there
            };
        } else {
            slack = 1.0;
            done = (x, y, z) -> Math.abs(x - g.getX()) <= 1 && Math.abs(z - g.getZ()) <= 1 && Math.abs(y - (g.getY() + 1)) <= 2;
        }
        java.util.Set<Long> bad = new HashSet<>();
        for (BlockPos b : learnedAvoid()) bad.add(Nav4.key(b.getX(), b.getY(), b.getZ()));
        List<int[]> ether = List.of();
        boolean canTp = useAotv && now >= noManaUntil && now >= nextEther && now >= aotvOffUntil && now >= navNoEtherUntil
                && findSlot(p, aotvName) >= 0;
        if (canTp) ether = etherCandidates(mc, p, p.getEyePosition(), 5.0);
        nav = new Nav4.Search(w, p.getBlockX(), sy, p.getBlockZ(), done, tx, ty, tz, slack, bad, ether, 0.6 * aotvMinDist, 30_000);
        navGoal = goal;
        lastPlan = now;
    }

    /** The search finished: turn its result into the path the follower walks (and a first teleport, if it wants one). */
    private void finishPlan(LocalPlayer p) {
        Nav4.Result r = nav.result();
        nav = null;
        pendingEther = null;
        if (r.path().isEmpty()) {
            leg = null;
            if (debug) Notify.msg("Path: none found (" + r.expanded() + " cells searched)");
            navFailGoal = goal; // no way on foot: do not search again at once, try a teleport instead
            navFailAt = System.currentTimeMillis();
            return;
        }
        List<Vec3> pts = new ArrayList<>();
        List<Planner.Move> moves = new ArrayList<>();
        for (Nav4.Node n : r.path()) {
            pts.add(new Vec3(n.x() + 0.5, n.y(), n.z() + 0.5));
            moves.add(switch (n.kind()) {
                case JUMP -> Planner.Move.STEP_UP;
                case FALL -> Planner.Move.DROP;
                case GAP -> Planner.Move.GAP;
                default -> Planner.Move.WALK;
            });
        }
        if (r.path().size() > 1 && r.path().get(1).kind() == Nav4.Kind.ETHER) {
            Nav4.Node e = r.path().get(1);
            pendingEther = new BlockPos(e.x(), e.y() - 1, e.z()); // the block to land on
            pendingSince = System.currentTimeMillis();
        }
        Nav4.Node last = r.path().get(r.path().size() - 1);
        leg = new Planner.Leg(pts, moves, List.of(goal), new BlockPos(last.x(), last.y(), last.z()), 0, r.cost());
        idx = 0;
        double bd = Double.MAX_VALUE; // start at the path point nearest to us (we moved while it searched)
        for (int i = 0; i < pts.size(); i++) {
            double d = pts.get(i).distanceToSqr(p.position());
            if (d < bd) {
                bd = d;
                idx = i;
            }
        }
        if (debug) Notify.msg(String.format(Locale.ROOT, "Path: %d points, %s, %d cells searched%s", pts.size(),
                r.complete() ? "reaches the goal" : "gets closer only", r.expanded(), pendingEther != null ? ", starts with a teleport" : ""));
    }

    /** Blocks the teleport can land on (seen in a fan of rays around us), nearest to the goal first. */
    private List<int[]> etherCandidates(Minecraft mc, LocalPlayer p, Vec3 eye, double minHop) {
        Vec3 to = goal.getCenter();
        List<BlockPos> cands = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (int pitch = -60; pitch <= 50; pitch += 6) {
            double cp = Math.cos(Math.toRadians(pitch)), sp = -Math.sin(Math.toRadians(pitch));
            int yawStep = Math.max(5, (int) Math.round(6.0 / Math.max(0.25, cp)));
            for (int yaw = 0; yaw < 360; yaw += yawStep) {
                double yr = Math.toRadians(yaw);
                Vec3 dir = new Vec3(-Math.sin(yr) * cp, sp, Math.cos(yr) * cp);
                BlockHitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(etherRange)),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (h.getType() != HitResult.Type.BLOCK) continue;
                BlockPos b = h.getBlockPos();
                if (!seen.add(b) || !landable(mc, b) || eye.distanceTo(b.getCenter()) < minHop) continue;
                cands.add(b);
            }
        }
        cands.sort((x, y) -> Double.compare(x.getCenter().distanceToSqr(to), y.getCenter().distanceToSqr(to)));
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(150, cands.size()); i++) {
            BlockPos b = cands.get(i);
            out.add(new int[]{b.getX(), b.getY() + 1, b.getZ()});
        }
        return out;
    }

    private void releaseMovement(Minecraft mc) {
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keySprint, mc.options.keyJump,
                mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight}) k.setDown(false);
    }

    private void releaseKeys(Minecraft mc) {
        releaseMovement(mc);
        sneak(mc, false);
        mc.options.keyAttack.setDown(false);
        etherStep = 0;
    }

    // ---- ESP ----------------------------------------------------------------------------------------------

    private void drawEsp() {
        for (BlockPos s : MudwormTracker.SPOTS.keySet()) {
            boolean cur = running && s.equals(goal), done = visited.containsKey(s);
            int stroke = cur ? 0xFFFFE030 : done ? 0xFF9A9A9A : 0xFF30FF60;
            int fill = cur ? 0x50FFE030 : done ? 0x309A9A9A : 0x4030FF60;
            Gizmos.cuboid(s, GizmoStyle.strokeAndFill(stroke, cur ? 2.5f : 2f, fill)).setAlwaysOnTop();
        }
        if (running && leg != null) {
            var pts = leg.path();
            for (int i = 0; i + 1 < pts.size(); i++)
                Gizmos.line(pts.get(i).add(0, 0.1, 0), pts.get(i + 1).add(0, 0.1, 0), 0xFF30E0FF, 3f).setAlwaysOnTop();
        }
        if (route != null) {
            for (int i = 0; i < route.size() && route.size() > 1; i++) {
                Vec3 a = route.get(i), b = route.get((i + 1) % route.size());
                Gizmos.line(a.add(0, 0.3, 0), b.add(0, 0.3, 0), 0xFFFF4040, 2f).setAlwaysOnTop();
            }
            for (int i = 0; i < route.size(); i++) {
                Vec3 w = route.get(i);
                Gizmos.cuboid(new net.minecraft.world.phys.AABB(w.x - 0.3, w.y, w.z - 0.3, w.x + 0.3, w.y + 0.6, w.z + 0.3),
                        GizmoStyle.stroke(0xFFFF4040, 2f)).setAlwaysOnTop();
            }
        }
    }

    // ---- settings screen ----------------------------------------------------------------------------------

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        ui.tab("Dashboard", "D");
        ui.section("Overview");
        ui.hero(() -> running ? stage + "  -  " + MudwormTracker.SPOTS.size() + " spots in sight"
                : "Press Start, close this screen, and go to the spots.", "Galatea mudworm spots");
        ui.tiles(() -> new String[][]{
                {"" + spotsDone, "Spots done"},
                {MudwormProfit.fmt(MudwormProfit.coins()), "Profit (coins)"},
                {MudwormProfit.fmt(runMs < 20_000 ? 0 : MudwormProfit.coins() / (runMs / 3_600_000.0)), "Coins / hour"},
                {"" + hops, "Teleports"},
                {"" + MudwormTracker.SPOTS.size(), "Spots known"},
                {clock(runMs), "Run time"},
        });
        ui.section("Last mudworm message");
        ui.info("Chat", "The last chat line that mentioned a Mudworm.", () -> lastMsg);
        ui.button("Reset statistics", "Sets the counters and the clock back to zero.", "Reset", false, () -> {
            spotsDone = 0;
            MudwormProfit.reset();
            hops = 0;
            mudwormMsgs = 0;
            lastMsg = "-";
            runMs = 0;
        });

        ui.tab("Movement", "M");
        ui.section("Aspect of the Void");
        ui.toggle("Use teleports", "Shift + right-click teleport (Ether Transmission) for the long hops. Needs the item in the hotbar and its Ether Transmission upgrade.",
                () -> useAotv, v -> useAotv = v);
        ui.text("Item name", "Part of the hotbar item name.", () -> aotvName, v -> aotvName = v, 150, "Aspect of the Void");
        ui.islider("Teleport range", "Farthest block to teleport onto (Ether Transmission reaches about 57).", 10, 70, () -> etherRange, v -> etherRange = v, " blocks");
        ui.islider("Shift-teleport farther than", "The macro walks by default. It shift + right-click teleports for hops farther than this, when the walk is much longer than the straight line, and when there is no way on foot.", 10, 100, () -> aotvMinDist, v -> aotvMinDist = v, " blocks");
        ui.toggle("Teleport messages", "Prints each shift + right-click and why a teleport failed, to find out what is wrong.", () -> debug, v -> debug = v);
        ui.section("Walking");
        ui.toggle("Sprint", "Sprint while walking along the path.", () -> sprint, v -> sprint = v);

        ui.tab("Action", "A");
        ui.section("On a spot");
        ui.choice("What to do", "What happens when you are next to a spot.", new String[]{"Right-click the block", "Right-click the air", "Left-click the spot"},
                () -> action, i -> action = i);
        ui.text("Tool", "Part of the hotbar item name to hold for it. Empty = whatever is in your hand.", () -> toolName, v -> toolName = v, 150, "current item");
        ui.islider("Delay after", "Pause after each spot (ms).", 100, 3000, () -> actDelayMs, v -> actDelayMs = v, " ms");
        ui.section("When the round is done");
        ui.choice("After a round", "When the route has been walked once and no spot is left: go to the hub and warp back for a new lobby, then repeat.", new String[]{"Stop at the end", "Hub + warp, repeat"}, () -> noSpotsAction, i -> noSpotsAction = i);
        ui.text("Hub command", "First command of the swap (without the slash).", () -> hubCommand, v -> hubCommand = v, 150, "hub");
        ui.text("Warp command", "Second command, to get back to the island (without the slash).", () -> warpCommand, v -> warpCommand = v, 150, "warp galatea");
        ui.islider("No route: wait", "Without a recorded route a round ends after this many seconds with no spot known.", 5, 180, () -> noSpotsSec, v -> noSpotsSec = v, " s");
        ui.info("Rounds / swaps", "Rounds started (each new lobby) and hub swaps this session.", () -> rounds + " / " + swaps);
        ui.section("Finding spots");
        ui.toggle("Only grass blocks", "Only count a spot when the block under the sparkles is a grass block (the mudworm spots are). Turn off to see every sparkle.",
                () -> grassOnly, v -> {
                    grassOnly = v;
                    MudwormTracker.grassOnly = v;
                    MudwormTracker.clear();
                });
        ui.text("Marker particles", "Particle ids that mark a spot, comma separated. Run /mudworm scan next to one to list what is around.",
                () -> particleIds, v -> particleIds = v, 190, "minecraft:happy_villager");
        ui.button("Forget spots", "Clears every known spot (they come back from the particles within a few seconds).", "Clear", false, MudwormTracker::clear);
        ui.info("ESP resets", "Times the ESP was reset because the lobby or world changed.", () -> MudwormTracker.resets + "");

        ui.tab("Route", "R");
        ui.section("Patrol route");
        ui.note("Soft route: the macro follows these waypoints in a loop (walking, or teleporting for long hops). When a spot is within the detour radius it goes to it first, then carries on from the closest waypoint.");
        ui.toggle("Soft route", "Follow the route and only detour to nearby spots. Off = always go to the nearest known spot first.", () -> softRoute, v -> softRoute = v);
        ui.toggle("Teleport along the route", "Face the next waypoint and spam the plain Aspect of the Void right-click (no shift). Shift-etherwarp is only used to go up. Off = walk the route.", () -> routeTp.enabled, v -> routeTp.enabled = v);
        ui.islider("Teleport delay", "Time between route right-clicks.", 120, 800, () -> routeTp.delayMs, v -> routeTp.delayMs = v, " ms");
        ui.islider("Right-click distance", "How far one plain Aspect of the Void right-click teleports (8 blocks; more with Transmission Tuners).", 4, 16, () -> routeTp.reachBlocks, v -> routeTp.reachBlocks = v, " blocks");
        ui.islider("Detour radius", "Spots farther than this from you are left for the route to reach.", 8, 100, () -> detourRadius, v -> detourRadius = v, " blocks");
        ui.info("Waypoints", "Recorded patrol points.", () -> route.size() + "");
        ui.info("Learned", "What the pathfinder learned from getting stuck and from failed teleports (kept between lobbies and restarts).", LEARNED::summary);
        ui.confirmButton("Forget what it learned", "Clears the blocked cells, bad landings and bad teleports.", "Forget", LEARNED::clear);
        ui.button("Add waypoint here", "Records the spot you are standing on (or press the Set waypoint key, P by default, in Controls).", "Add", true, () -> {
            var p = Minecraft.getInstance().player;
            if (p != null) route.add(p.getX(), p.getY(), p.getZ());
        });
        ui.button("Remove last waypoint", "Undo the last recorded point.", "Undo", false, route::removeLast);
        ui.confirmButton("Clear route", "Deletes every waypoint.", "Clear", route::clear);
        ui.confirmButton("Load the video's route", "Replaces the route with the points read from the video (approximate and unverified: check them with the ESP and fix them by recording your own).", "Load", () -> {
            route.clear();
            double[][] pts = {{369, 75, 49}, {375, 78, 62}, {393, 116, 23}, {410, 151, -12}, {393, 147, -25}, {394, 150, -42},
                    {383, 131, -55}, {394, 129, -15}, {390, 119, -4}, {382, 110, 17}, {362, 75, 46}};
            for (double[] q : pts) route.add(q[0], q[1], q[2]);
        });

        ui.tab("Display", "V");
        ui.section("Overlays");
        ui.toggle("Profit panel", "Top right: time, spots, profit, coins per hour and the best drops.", () -> hud, v -> hud = v);
        ui.toggle("ESP", "Every spot found on the island is remembered (until it is broken, collected, or the lobby changes) and shown; boxes on the spots (green = target, yellow = going there, grey = done), the path and your waypoints.", () -> esp, v -> esp = v);
    }

    private static String clock(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    // ---- persistence --------------------------------------------------------------------------------------

    private void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            applyMarkers();
            return;
        }
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            esp = Boolean.parseBoolean(p.getProperty("esp", "" + esp));
            useAotv = Boolean.parseBoolean(p.getProperty("useAotv", "" + useAotv));
            aotvName = p.getProperty("aotvName", aotvName);
            etherRange = Integer.parseInt(p.getProperty("etherRange", "" + etherRange).trim());
            aotvMinDist = Integer.parseInt(p.getProperty("aotvMinDist4", "" + aotvMinDist).trim());
            sprint = Boolean.parseBoolean(p.getProperty("sprint", "" + sprint));
            action = Math.max(0, Math.min(2, Integer.parseInt(p.getProperty("action2", "" + action).trim())));
            toolName = p.getProperty("toolName2", toolName);
            hud = Boolean.parseBoolean(p.getProperty("hud", "" + hud));
            noSpotsAction = Math.max(0, Math.min(1, Integer.parseInt(p.getProperty("noSpotsAction", "" + noSpotsAction).trim())));
            noSpotsSec = Integer.parseInt(p.getProperty("noSpotsSec", "" + noSpotsSec).trim());
            warpCommand = p.getProperty("warpCommand", warpCommand);
            hubCommand = p.getProperty("hubCommand", hubCommand);
            actDelayMs = Integer.parseInt(p.getProperty("actDelayMs", "" + actDelayMs).trim());
            particleIds = p.getProperty("particleIds", particleIds);
            grassOnly = Boolean.parseBoolean(p.getProperty("grassOnly", "" + grassOnly));
            softRoute = Boolean.parseBoolean(p.getProperty("softRoute", "" + softRoute));
            detourRadius = Integer.parseInt(p.getProperty("detourRadius", "" + detourRadius).trim());
            routeTp.enabled = Boolean.parseBoolean(p.getProperty("routeTp", "" + routeTp.enabled));
            routeTp.delayMs = Integer.parseInt(p.getProperty("tpDelayMs", "" + routeTp.delayMs).trim());
            routeTp.reachBlocks = Integer.parseInt(p.getProperty("tpReach", "" + routeTp.reachBlocks).trim());
        } catch (Exception ignored) {
            // keep defaults
        }
        applyMarkers();
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("esp", "" + esp);
        p.setProperty("useAotv", "" + useAotv);
        p.setProperty("aotvName", aotvName);
        p.setProperty("etherRange", "" + etherRange);
        p.setProperty("aotvMinDist4", "" + aotvMinDist);
        p.setProperty("sprint", "" + sprint);
        p.setProperty("action2", "" + action);
        p.setProperty("toolName2", toolName);
        p.setProperty("hud", "" + hud);
        p.setProperty("noSpotsAction", "" + noSpotsAction);
        p.setProperty("noSpotsSec", "" + noSpotsSec);
        p.setProperty("warpCommand", warpCommand);
        p.setProperty("hubCommand", hubCommand);
        p.setProperty("actDelayMs", "" + actDelayMs);
        p.setProperty("particleIds", particleIds);
        p.setProperty("grassOnly", "" + grassOnly);
        p.setProperty("softRoute", "" + softRoute);
        p.setProperty("detourRadius", "" + detourRadius);
        p.setProperty("routeTp", "" + routeTp.enabled);
        p.setProperty("tpDelayMs", "" + routeTp.delayMs);
        p.setProperty("tpReach", "" + routeTp.reachBlocks);
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Nablzz Addons: mudworm macro");
        } catch (IOException ignored) {
            // not saved
        }
    }
}
