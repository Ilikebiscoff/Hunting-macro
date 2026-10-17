package com.nablzz.addons.mudworm;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nablzz.addons.Feature;
import com.nablzz.addons.Features;
import com.nablzz.addons.NablzzAddons;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.Notify;
import com.nablzz.addons.minerzombie.HumanAim;
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
    /** Spots more than this many blocks above or below you are ignored. */
    public volatile int yRange = 10;
    public volatile String particleIds = "minecraft:happy_villager";

    // ---- state ----
    private volatile boolean running;
    private final Walker walker = new Walker();
    private final Warp warp = new Warp();
    private boolean[] wpDone;
    private final HumanAim aim = new HumanAim();
    private Route route;
    private int waypoint;
    private boolean afterSpot, lapDone;
    private int seenResets, resetsAtWarp;
    private long roundStart;
    private volatile int rounds;
    private long runMs, lastTick;
    private String stage = "Stopped";

    private BlockPos goal;
    private boolean goalIsSpot;
    private final Map<BlockPos, Long> visited = new HashMap<>();
    private final Set<BlockPos> avoid = new HashSet<>();
    private static final Learned LEARNED = new Learned();
    private long lastPlan, goalSince, nextAction;

    // teleport (see Warp)
    private long nextEther, aotvOffUntil;
    private int etherFails;
    // path finder v4 (see Nav4): the running search, the teleport it asked for, and the wall bookkeeping
    private Nav4.Search nav;
    private BlockPos navGoal;
    private long navFailAt;
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
                warp.serverRefused();
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
        walker.clear();
        nav = null;
        warp.forget();
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
        walker.clear();
        nav = null;
        wpDone = null;
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
                + ", route waypoints " + (route == null ? 0 : route.size()) + ", walked " + doneCount() + ", lap done " + lapDone);
        out.accept("spots known: " + MudwormTracker.SPOTS.size() + ", lobby/world resets " + MudwormTracker.resets);
        int n = 0;
        StringBuilder sp = new StringBuilder();
        for (BlockPos b : MudwormTracker.SPOTS.keySet()) {
            if (n++ >= 12) break;
            sp.append(b.toShortString()).append("; ");
        }
        if (n > 0) out.accept("first spots: " + sp);
        out.accept("learned: " + LEARNED.summary());
        out.accept("teleport state: " + (warp.active() ? "running" : "idle") + ", fails in a row " + etherFails
                + ", no-mana for " + Math.max(0, (noManaUntil - now) / 1000) + " s, paused for " + Math.max(0, (aotvOffUntil - now) / 1000)
                + " s");
        out.accept("last teleport problem: " + lastEtherFail);
        out.accept("settings: aotv " + useAotv + " \"" + aotvName + "\", shift-teleport beyond " + aotvMinDist + " blocks, range " + etherRange
                + ", sprint " + sprint
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

        if (warp.active()) { // a teleport is in progress: nothing else moves
            warpTick(mc, p, now);
            return;
        }

        chooseGoal(p, now);
        boolean hasRoute = route != null && route.size() > 0;
        if (hasRoute && routeFinished()) lapDone = true;
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
            markReached(waypoint);
            waypoint++;
            goal = null;
            return;
        }
        // walking is the default; shift + right-click only for long hops, or when the way on foot is much longer than the
        // straight line. When there is no way on foot at all, walk() teleports over by itself.
        boolean detour = walker.hasPath() && hd > 12.0 && walker.remaining(p) > 1.8 * hd;
        if (hd > aotvMinDist || detour) {
            if (!warpReady(p, now)) {
                tpMsg(now, "wants to teleport (" + (int) hd + " blocks away) but: " + whyNotReady(p, now));
            } else if (startWarp(mc, p, now, 5.0)) {
                return;
            }
        }

        walk(mc, p, now);
    }

    // ---- the route: which waypoint is next ----

    private void markReached(int i) {
        if (route == null || route.size() == 0) return;
        ensureDone();
        wpDone[Math.floorMod(i, route.size())] = true;
    }

    private void ensureDone() {
        int n = route == null ? 0 : route.size();
        if (wpDone == null || wpDone.length != n) wpDone = new boolean[n];
    }

    private int doneCount() {
        ensureDone();
        int n = 0;
        for (boolean b : wpDone) if (b) n++;
        return n;
    }

    private boolean routeFinished() {
        ensureDone();
        for (boolean b : wpDone) if (!b) return false;
        return wpDone.length > 0;
    }

    /** The waypoint closest to where we stand, among those not walked yet; -1 when all are done. */
    private int nearestWaypoint(LocalPlayer p) {
        ensureDone();
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            if (wpDone[i]) continue;
            double d = route.get(i).distanceToSqr(p.position());
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
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
        walker.clear();
        nav = null;
        goalSince = now;
        if (spot != null) {
            goal = spot;
            goalIsSpot = true;
            return;
        }
        if (route != null && route.size() > 0 && !lapDone) {
            if (afterSpot) { // back from a detour (or a fresh round): the nearest waypoint, wherever it is on the route
                afterSpot = false;
                int near = nearestWaypoint(p);
                if (near < 0) {
                    lapDone = true;
                    return;
                }
                waypoint = near;
            } else { // otherwise the next waypoint in order that is not done yet
                ensureDone();
                int n = route.size(), pick = -1;
                for (int k = 0; k < n && pick < 0; k++) {
                    int i = Math.floorMod(waypoint + k, n);
                    if (!wpDone[i]) pick = i;
                }
                if (pick < 0) {
                    lapDone = true;
                    return;
                }
                waypoint = pick;
            }
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
            if (Math.abs(s.getY() - p.getY()) > yRange) continue; // too far above / below: not ours
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

    // ---- walking ------------------------------------------------------------------------------------------

    private void walk(Minecraft mc, LocalPlayer p, long now) {
        stage = goalIsSpot ? "Walking to a spot" : "Walking the route";
        if (nav != null && !goal.equals(navGoal)) nav = null; // the goal changed while it was searching
        if (nav != null && nav.step(3_000_000L)) finishPlan(p);
        boolean failedRecently = goal.equals(navFailGoal) && now - navFailAt < 2500;
        if (nav == null && !failedRecently && ((!walker.hasPath() && now - lastPlan > 400) || now - lastPlan > 6000)) startPlan(mc, p, now);

        if (!walker.hasPath()) {
            releaseMovement(mc);
            Vec3 c = goal.getCenter();
            float[] look = angles(p.getEyePosition(), c);
            aim.setTarget(look[0], 8f, 520f, 1f); // keep facing the goal instead of freezing the camera
            if (nav != null) { // still searching
                stage = "Planning the path";
                return;
            }
            if (now - goalSince > 1500 && warpReady(p, now) && startWarp(mc, p, now, 3.0)) return; // no way on foot: teleport over
            stage = "No path";
            if (now - goalSince > 4000) { // nothing reachable: skip it for a while
                visited.put(goal, now);
                if (!goalIsSpot) markReached(waypoint); // a waypoint we cannot reach still counts as done
                if (!goalIsSpot) waypoint++;
                goal = null;
            }
            return;
        }

        Walker.Status s = walker.drive(mc, p, now, aim, sprint);
        if (etherIdle()) Warp.sneak(mc, false);
        if (s == Walker.Status.ARRIVED) { // end of this path: plan again from here (the goal may need more)
            walker.clear();
            lastPlan = 0;
            releaseMovement(mc);
        } else if (s == Walker.Status.BLOCKED) {
            BlockPos at = walker.blockedAt();
            if (debug) Notify.msg("Path: blocked at " + at.toShortString() + " (" + walker.blockedWhy() + "), planning again.");
            avoid.add(at);
            if (++wallStrikes >= 2) LEARNED.addAvoid(at); // the second strike on a cell is remembered for later lobbies
            walker.clear();
            lastPlan = 0;
            releaseMovement(mc);
            stage = "Blocked - planning around it";
        } else {
            wallStrikes = 0;
        }
    }

    private boolean etherIdle() {
        return !warp.active();
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

    private boolean warpReady(LocalPlayer p, long now) {
        return useAotv && now >= noManaUntil && now >= nextEther && now >= aotvOffUntil && findSlot(p, aotvName) >= 0;
    }

    /**
     * Looks around (rays in every direction), takes the block that is really in sight and gets us closest to the goal
     * (for a spot: one from which the spot can be dug), and starts the teleport. False = no good block right now.
     */
    private boolean startWarp(Minecraft mc, LocalPlayer p, long now, double minHop) {
        Vec3 eye = p.getEyePosition();
        Vec3 to = goal.getCenter();
        double cur = eye.distanceTo(to);
        List<Warp.Pick> picks = Warp.scan(mc, p, etherRange, minHop, b -> warp.isBanned(b) || LEARNED.badLanding(b));
        Warp.Pick best = null;
        double bestScore = Double.MAX_VALUE;
        Vec3 spotTop = topOf(goal);
        for (Warp.Pick k : picks) {
            BlockPos b = k.block();
            Vec3 stand = new Vec3(b.getX() + 0.5, b.getY() + 1.0 + 1.62, b.getZ() + 0.5);
            double d = stand.distanceTo(to);
            if (d > cur - 3.0) continue; // must get us closer
            double score = d;
            if (goalIsSpot && stand.distanceTo(spotTop) <= ACT_REACH - 0.3) score -= 100; // can dig the spot from there
            if (score < bestScore) {
                bestScore = score;
                best = k;
            }
        }
        if (best == null) {
            tpMsg(now, "no block in sight that gets closer to the goal (looked in every direction, range " + etherRange + ").");
            nextEther = now + 1200;
            return false;
        }
        int slot = findSlot(p, aotvName);
        walker.clear();
        nav = null;
        warp.begin(best, slot, p, now);
        stage = "Teleporting";
        releaseMovement(mc);
        Warp.sneak(mc, true); // shift is held through the whole teleport
        if (debug) Notify.msg(String.format(Locale.ROOT, "Teleport: aiming at the block %s (%.0f blocks away)", best.block().toShortString(), eye.distanceTo(best.block().getCenter())));
        return true;
    }

    private void warpTick(Minecraft mc, LocalPlayer p, long now) {
        Warp.Result r = warp.tick(mc, p, now, aim, etherRange, debug);
        if (r == Warp.Result.BUSY) return;
        if (r == Warp.Result.DONE) {
            hops++;
            etherFails = 0;
            nextEther = now + 350;
            lastPlan = 0; // plan again from the new spot
            walker.clear();
            return;
        }
        lastEtherFail = warp.why();
        if (warp.current() != null) LEARNED.addBadLanding(warp.current().block());
        Notify.msg("Teleport failed: " + warp.why());
        nextEther = now + 1500;
        if (++etherFails >= 3) {
            etherFails = 0;
            aotvOffUntil = now + 30_000;
            Notify.msg("The teleport keeps failing (Ether Transmission needs the upgrade, and a clear view of the block) - walking for 30 s.");
        }
    }

    private volatile String lastEtherFail = "none";

    // ---- lobby swap: /lobby, /skyblock, warp back ----------------------------------------------------------------

    private void startLobbySwap(Minecraft mc, LocalPlayer p, long now) {
        releaseKeys(mc);
        goal = null;
        walker.clear();
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
    private Set<BlockPos> learnedAvoid() {
        Set<BlockPos> all = LEARNED.avoid();
        all.addAll(avoid);
        return all;
    }

    // ---- path finder glue ---------------------------------------------------------------------------------

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
        final Vec3 top = topOf(g);
        Nav4.Goal done;
        int tx = g.getX(), ty = g.getY() + 1, tz = g.getZ();
        double slack;
        if (goalIsSpot) {
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
        nav = new Nav4.Search(w, p.getBlockX(), sy, p.getBlockZ(), done, tx, ty, tz, slack, bad, null, 0, 30_000);
        navGoal = goal;
        lastPlan = now;
    }

    /** The search finished: hand its path to the walker. */
    private void finishPlan(LocalPlayer p) {
        Nav4.Result r = nav.result();
        nav = null;
        if (r.path().size() < 2) {
            walker.clear();
            if (debug) Notify.msg("Path: none found (" + r.expanded() + " cells searched)");
            navFailGoal = goal; // no way on foot: do not search again at once, try a teleport instead
            navFailAt = System.currentTimeMillis();
            return;
        }
        walker.set(r.path(), p.position());
        if (debug) Notify.msg(String.format(Locale.ROOT, "Path: %d points, %s, %d cells searched", r.path().size(),
                r.complete() ? "reaches the goal" : "gets closer only", r.expanded()));
    }

    private void releaseMovement(Minecraft mc) {
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keySprint, mc.options.keyJump,
                mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight}) k.setDown(false);
    }

    private void releaseKeys(Minecraft mc) {
        releaseMovement(mc);
        Warp.sneak(mc, false);
        mc.options.keyAttack.setDown(false);
        warp.abort(mc);
    }

    // ---- ESP ----------------------------------------------------------------------------------------------

    private void drawEsp() {
        for (BlockPos s : MudwormTracker.SPOTS.keySet()) {
            boolean cur = running && s.equals(goal), done = visited.containsKey(s);
            int stroke = cur ? 0xFFFFE030 : done ? 0xFF9A9A9A : 0xFF30FF60;
            int fill = cur ? 0x50FFE030 : done ? 0x309A9A9A : 0x4030FF60;
            Gizmos.cuboid(s, GizmoStyle.strokeAndFill(stroke, cur ? 2.5f : 2f, fill)).setAlwaysOnTop();
        }
        if (running && walker.hasPath()) {
            var pts = walker.points();
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
        ui.note("Soft route: the macro walks these waypoints (shift + right-click teleport only for long hops). When a spot is within the detour radius it goes to it first, then carries on from the waypoint nearest to where it ended up.");
        ui.toggle("Soft route", "Follow the route and only detour to nearby spots. Off = always go to the nearest known spot first.", () -> softRoute, v -> softRoute = v);
        ui.islider("Height range", "Spots more than this many blocks above or below you are ignored.", 3, 60, () -> yRange, v -> yRange = v, " blocks");
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
            yRange = Integer.parseInt(p.getProperty("yRange", "" + yRange).trim());
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
        p.setProperty("yRange", "" + yRange);
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Nablzz Addons: mudworm macro");
        } catch (IOException ignored) {
            // not saved
        }
    }
}

