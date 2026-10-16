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
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
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
    public volatile int aotvMinDist = 14;
    public volatile boolean sprint = true;
    /** 0 = right-click the block, 1 = right-click in the air, 2 = break the block. */
    public volatile int action = 0;
    /** Hotbar item for the action (part of its name); empty = whatever is in your hand. */
    public volatile String toolName = "";
    public volatile int actDelayMs = 500;
    public volatile String particleIds = "minecraft:happy_villager";

    // ---- state ----
    private volatile boolean running;
    private final WalkCache cache = new WalkCache();
    private final Planner planner = new Planner();
    private final HumanAim aim = new HumanAim();
    private Route route;
    private int waypoint;
    private long runMs, lastTick;
    private String stage = "Stopped";

    private BlockPos goal;
    private boolean goalIsSpot;
    private final Map<BlockPos, Long> visited = new HashMap<>();
    private final Set<BlockPos> avoid = new HashSet<>();
    private Planner.Leg leg;
    private int idx;
    private long lastPlan, goalSince, nextAction, stuckAt;
    private Vec3 stuckFrom;

    // etherwarp state machine
    private int etherStep;
    private BlockPos etherBlock;
    private long etherAt, nextEther, aotvOffUntil;
    private Vec3 etherFrom;
    private int etherFails, swapTicks;

    // stats
    private volatile int spotsDone, hops, mudwormMsgs;
    private volatile String lastMsg = "-";

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablzz_mudworm.properties");
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
        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String s = message.getString();
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
        visited.clear();
        avoid.clear();
        aim.reset();
        running = true;
        stage = "Searching";
        Notify.msg("Mudworm macro started" + (useAotv && findSlot(mc.player, aotvName) < 0
                ? " (no \"" + aotvName + "\" in the hotbar: walking only)." : "."));
    }

    @Override
    public void stop(Minecraft mc) {
        if (!running) return;
        running = false;
        stage = "Stopped";
        releaseKeys(mc);
        Notify.msg("Mudworm macro stopped: " + spotsDone + " spots done, " + hops + " teleports.");
    }

    @Override
    public void saveSettings() {
        save();
        applyMarkers();
    }

    private void applyMarkers() {
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
        MudwormTracker.enabled = esp || running;
        if (MudwormTracker.enabled || MudwormTracker.scanning) MudwormTracker.tick(mc);
        if (!running) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.screen != null) {
            releaseKeys(mc);
            return;
        }
        cache.tick(mc);
        visited.values().removeIf(t -> now - t > VISITED_MS);

        if (etherStep != 0) { // a teleport is in progress: nothing else moves
            etherTick(mc, p, now);
            return;
        }

        chooseGoal(p, now);
        if (goal == null) {
            stage = "Searching";
            releaseMovement(mc);
            return;
        }

        Vec3 eye = p.getEyePosition();
        Vec3 top = topOf(goal);
        double hd = Math.hypot(top.x - p.getX(), top.z - p.getZ());

        if (goalIsSpot && eye.distanceTo(top) <= ACT_REACH && visible(mc, p, eye, goal)) {
            act(mc, p, now, top);
            return;
        }
        if (!goalIsSpot && hd < 2.5) { // reached a patrol waypoint
            waypoint++;
            goal = null;
            return;
        }
        if (aotvReady(mc, p, now) && Math.hypot(top.x - p.getX(), top.z - p.getZ()) > aotvMinDist && tryEther(mc, p, now, eye)) return;

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
        if (route != null && route.size() > 0) {
            waypoint = waypoint % route.size();
            Vec3 w = route.get(waypoint);
            goal = BlockPos.containing(w.x, w.y - 0.5, w.z);
            goalIsSpot = false;
        }
    }

    private BlockPos nearestSpot(LocalPlayer p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos s : MudwormTracker.SPOTS.keySet()) {
            if (visited.containsKey(s)) continue;
            double d = s.getCenter().distanceToSqr(p.position());
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    // ---- the action on a spot -----------------------------------------------------------------------------

    private void act(Minecraft mc, LocalPlayer p, long now, Vec3 top) {
        stage = "Digging";
        releaseMovement(mc);
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
                mc.gameMode.startDestroyBlock(goal, Direction.UP);
                mc.options.keyAttack.setDown(true);
            }
            default -> mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(top, Direction.UP, goal, false));
        }
        p.swing(InteractionHand.MAIN_HAND);
        spotsDone++;
        visited.put(goal, now);
        goal = null;
        nextAction = now + actDelayMs;
        if (action == 2) nextAction = now + Math.max(actDelayMs, 1200);
        mc.options.keyAttack.setDown(false);
    }

    // ---- walking (pathfinder) -----------------------------------------------------------------------------

    private void walk(Minecraft mc, LocalPlayer p, long now) {
        stage = goalIsSpot ? "Walking to a spot" : "Walking the route";
        Planner.Plan plan = planner.poll();
        if (plan != null && !plan.legs().isEmpty()) {
            leg = plan.legs().get(0);
            idx = 0;
        }
        if ((leg == null && now - lastPlan > 500) || now - lastPlan > 2500) {
            if (planner.request(cache.grid(), p.blockPosition(), List.of(goal), Set.of(), new HashSet<>(avoid))) lastPlan = now;
        }
        Vec3 target;
        if (leg == null || leg.path().isEmpty()) {
            Vec3 c = goal.getCenter();
            if (cache.grid() != null && Planner.walkable(cache.grid(), p.position(), c)) target = c; // straight line is safe
            else {
                releaseMovement(mc);
                stage = "No path";
                if (now - goalSince > 8000) { // nothing reachable: skip it for a while
                    visited.put(goal, now);
                    goal = null;
                }
                return;
            }
        } else {
            List<Vec3> pts = leg.path();
            while (idx < pts.size() - 1 && Math.hypot(pts.get(idx).x - p.getX(), pts.get(idx).z - p.getZ()) < 0.9
                    && Math.abs(pts.get(idx).y - p.getY()) < 1.5) idx++;
            target = pts.get(Math.min(idx + 1, pts.size() - 1));
        }
        float yaw = (float) (Math.atan2(target.z - p.getZ(), target.x - p.getX()) * 180.0 / Math.PI) - 90f;
        aim.setTarget(yaw, 8f, 520f, 1f);
        float err = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - p.getYRot()));
        mc.options.keyUp.setDown(err < 70f);
        mc.options.keySprint.setDown(sprint && err < 25f);
        mc.options.keyJump.setDown(p.onGround() && (p.horizontalCollision || target.y > p.getY() + 0.6));
        mc.options.keyShift.setDown(false);

        // stuck: not 2 blocks further in 5 s
        if (stuckFrom == null || p.position().distanceTo(stuckFrom) > 2.0) {
            stuckFrom = p.position();
            stuckAt = now;
        } else if (now - stuckAt > 5000) {
            stuckAt = now;
            if (leg != null && !leg.path().isEmpty()) avoid.add(BlockPos.containing(leg.path().get(Math.min(idx + 1, leg.path().size() - 1))));
            leg = null;
            lastPlan = 0;
            if (now - goalSince > 15_000 && goal != null) {
                visited.put(goal, now);
                goal = null;
            }
        }
    }

    // ---- Aspect of the Void: shift + right-click --------------------------------------------------------------

    private boolean aotvReady(Minecraft mc, LocalPlayer p, long now) {
        return useAotv && now >= nextEther && now >= aotvOffUntil && findSlot(p, aotvName) >= 0;
    }

    /** Picks a block to teleport onto and starts the sequence. False = no good teleport right now. */
    private boolean tryEther(Minecraft mc, LocalPlayer p, long now, Vec3 eye) {
        BlockPos pick = null;
        if (goalIsSpot && inRange(eye, goal) && visible(mc, p, eye, goal)) {
            pick = goal; // straight onto the spot
        } else if (leg != null) {
            List<Vec3> pts = leg.path();
            for (int i = pts.size() - 1; i > idx && pick == null; i--) {
                Vec3 v = pts.get(i);
                BlockPos floor = BlockPos.containing(v.x, v.y - 0.5, v.z);
                double d = eye.distanceTo(floor.getCenter());
                if (d >= aotvMinDist && d <= etherRange && visible(mc, p, eye, floor)) pick = floor;
            }
        }
        if (pick == null) {
            nextEther = now + 1500;
            return false;
        }
        etherBlock = pick;
        etherStep = 1;
        etherAt = now;
        swapTicks = 4;
        stage = "Teleporting";
        releaseMovement(mc);
        int slot = findSlot(p, aotvName);
        if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
        return true;
    }

    private void etherTick(Minecraft mc, LocalPlayer p, long now) {
        Vec3 aimPt = new Vec3(etherBlock.getX() + 0.5, etherBlock.getY() + 0.9, etherBlock.getZ() + 0.5);
        switch (etherStep) {
            case 1 -> { // look at the block
                float[] a = angles(p.getEyePosition(), aimPt);
                aim.setTarget(a[0], a[1], 900f, 1.3f);
                if (swapTicks > 0) swapTicks--;
                boolean onIt = mc.hitResult instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK
                        && h.getBlockPos().equals(etherBlock);
                if (swapTicks == 0 && aim.settled(p, 2f) && onIt) {
                    mc.options.keyShift.setDown(true);
                    etherStep = 2;
                    etherAt = now;
                } else if (now - etherAt > 2500) {
                    etherFail(mc, now);
                }
            }
            case 2 -> { // sneak is registered after a couple of ticks, then the click
                if (now - etherAt >= 130) {
                    etherFrom = p.position();
                    mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    etherStep = 3;
                    etherAt = now;
                }
            }
            default -> { // wait for the teleport
                if (p.position().distanceTo(etherFrom) > 3.0) {
                    hops++;
                    etherFails = 0;
                    mc.options.keyShift.setDown(false);
                    etherStep = 0;
                    nextEther = now + 350;
                    leg = null; // re-plan from the new spot
                    lastPlan = 0;
                    idx = 0;
                } else if (now - etherAt > 900) {
                    etherFail(mc, now);
                }
            }
        }
    }

    private void etherFail(Minecraft mc, long now) {
        mc.options.keyShift.setDown(false);
        etherStep = 0;
        nextEther = now + 1500;
        if (++etherFails >= 3) {
            etherFails = 0;
            aotvOffUntil = now + 30_000;
            Notify.msg("The teleport keeps failing (Ether Transmission needs the upgrade, and a clear view of the block) - walking for 30 s.");
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private boolean inRange(Vec3 eye, BlockPos b) {
        double d = eye.distanceTo(b.getCenter());
        return d >= aotvMinDist && d <= etherRange;
    }

    /** True when a ray from the eyes to the top of the block hits that block first. */
    private static boolean visible(Minecraft mc, LocalPlayer p, Vec3 eye, BlockPos b) {
        Vec3 to = new Vec3(b.getX() + 0.5, b.getY() + 0.9, b.getZ() + 0.5);
        BlockHitResult h = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(b);
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

    private void releaseMovement(Minecraft mc) {
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keySprint, mc.options.keyJump,
                mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight}) k.setDown(false);
    }

    private void releaseKeys(Minecraft mc) {
        releaseMovement(mc);
        mc.options.keyShift.setDown(false);
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
                {"" + MudwormTracker.SPOTS.size(), "Spots known"},
                {"" + hops, "Teleports"},
                {"" + mudwormMsgs, "Mudworm chat lines"},
                {clock(runMs), "Run time"},
                {String.format(Locale.ROOT, "%.0f", runMs < 20_000 ? 0 : spotsDone / (runMs / 3_600_000.0)), "Spots / hour"},
        });
        ui.section("Last mudworm message");
        ui.info("Chat", "The last chat line that mentioned a Mudworm.", () -> lastMsg);
        ui.button("Reset statistics", "Sets the counters and the clock back to zero.", "Reset", false, () -> {
            spotsDone = 0;
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
        ui.islider("Walk when closer than", "Closer than this the macro walks instead of teleporting.", 5, 40, () -> aotvMinDist, v -> aotvMinDist = v, " blocks");
        ui.section("Walking");
        ui.toggle("Sprint", "Sprint while walking along the path.", () -> sprint, v -> sprint = v);

        ui.tab("Action", "A");
        ui.section("On a spot");
        ui.choice("What to do", "What happens when you are next to a spot.", new String[]{"Right-click the block", "Right-click the air", "Break the block"},
                () -> action, i -> action = i);
        ui.text("Tool", "Part of the hotbar item name to hold for it. Empty = whatever is in your hand.", () -> toolName, v -> toolName = v, 150, "current item");
        ui.islider("Delay after", "Pause after each spot (ms).", 100, 3000, () -> actDelayMs, v -> actDelayMs = v, " ms");
        ui.section("Finding spots");
        ui.text("Marker particles", "Particle ids that mark a spot, comma separated. Run /mudworm scan next to one to list what is around.",
                () -> particleIds, v -> particleIds = v, 190, "minecraft:happy_villager");
        ui.button("Forget spots", "Clears every known spot.", "Clear", false, MudwormTracker::clear);

        ui.tab("Route", "R");
        ui.section("Patrol route");
        ui.note("When no spot is in sight the macro walks (or teleports) between these waypoints in a loop.");
        ui.info("Waypoints", "Recorded patrol points.", () -> route.size() + "");
        ui.button("Add waypoint here", "Records the spot you are standing on.", "Add", true, () -> {
            var p = Minecraft.getInstance().player;
            if (p != null) route.add(p.getX(), p.getY(), p.getZ());
        });
        ui.button("Remove last waypoint", "Undo the last recorded point.", "Undo", false, route::removeLast);
        ui.confirmButton("Clear route", "Deletes every waypoint.", "Clear", route::clear);

        ui.tab("Display", "V");
        ui.section("Overlays");
        ui.toggle("ESP", "Boxes on the spots (green = target, yellow = going there, grey = done), the path and your waypoints.", () -> esp, v -> esp = v);
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
            aotvMinDist = Integer.parseInt(p.getProperty("aotvMinDist", "" + aotvMinDist).trim());
            sprint = Boolean.parseBoolean(p.getProperty("sprint", "" + sprint));
            action = Math.max(0, Math.min(2, Integer.parseInt(p.getProperty("action", "" + action).trim())));
            toolName = p.getProperty("toolName", toolName);
            actDelayMs = Integer.parseInt(p.getProperty("actDelayMs", "" + actDelayMs).trim());
            particleIds = p.getProperty("particleIds", particleIds);
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
        p.setProperty("aotvMinDist", "" + aotvMinDist);
        p.setProperty("sprint", "" + sprint);
        p.setProperty("action", "" + action);
        p.setProperty("toolName", toolName);
        p.setProperty("actDelayMs", "" + actDelayMs);
        p.setProperty("particleIds", particleIds);
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Nablzz Addons: mudworm macro");
        } catch (IOException ignored) {
            // not saved
        }
    }
}
