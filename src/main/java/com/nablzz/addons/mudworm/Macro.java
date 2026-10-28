package com.nablzz.addons.mudworm;

import com.nablzz.addons.mudworm.Chat;
import com.nablzz.addons.mudworm.Config;
import com.nablzz.addons.mudworm.Etherwarp;
import com.nablzz.addons.mudworm.HumanAim;
import com.nablzz.addons.mudworm.SpotScanner;
import com.nablzz.addons.mudworm.path.Cell;
import com.nablzz.addons.mudworm.path.Pathfinder;
import com.nablzz.addons.mudworm.path.WorldCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class Macro {
    public static final Macro INSTANCE = new Macro();
    private static final long WARP_TIMEOUT_MS = 1500L;
    private static final long SKIP_MS = 60000L;
    private static final long PATROL_REVISIT_MS = 120000L;
    private static final double PATROL_RADIUS = 10.0;
    private static final double MAX_GOAL_DIST = 90.0;
    private static final long PLAN_TIMEOUT_MS = 6000L;
    private static final long LOBBY_SETTLE_MS = 6000L;
    private static final long REFRESH_GAP_MS = 3500L;
    private static final int MAX_REFRESHES_IN_A_ROW = 8;
    private static final int NUKE_GAP_TICKS = 3;
    private static final long NUKE_RETRY_MS = 500L;
    private static final double SWITCH_MARGIN = 1.0;
    private static final int MAX_ROUTE_ROUNDS = 6;
    private static final long DIG_LIMIT_MS = 2000L;
    public final HumanAim aim = new HumanAim();
    private final WorldCache cache = new WorldCache();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mudworm-planner");
        t.setDaemon(true);
        return t;
    });
    private boolean running;
    private Stage stage = Stage.CHOOSE;
    private String status = "Stopped";
    private ClientLevel startLevel;
    private boolean terrainWait;
    private int idleStrikes;
    private Pathfinder.Goal goal;
    private BlockPos approaching;
    private final Map<BlockPos, Integer> planFails = new HashMap<BlockPos, Integer>();
    private Pathfinder planner;
    private Pathfinder.Limits lastLimits;
    private long blockedAt;
    private long planStart;
    private volatile List<Pathfinder.Step> steps = new ArrayList<Pathfinder.Step>();
    private volatile int stepIdx;
    private BlockPos patrolPoint;
    private int replans;
    private int routeRounds;
    private final Set<Long> avoid = new HashSet<Long>();
    private final Map<BlockPos, Long> skipUntil = new HashMap<BlockPos, Long>();
    private final Map<BlockPos, Long> patrolVisited = new HashMap<BlockPos, Long>();
    private Vec3 moveRef;
    private long moveRefAt;
    private boolean warping;
    private Vec3 warpPos;
    private ClientLevel warpLevel;
    private long warpWaitUntil;
    private long resumeAt;
    private int warpsSinceSpot;
    private Etherwarp.Shot shot;
    private long prepSince;
    private long sneakSince;
    private long warpAt;
    private long readySince;
    private Vec3 warpFrom;
    private int warpFails;
    private long etherOffUntil;
    private long noManaUntil;
    private long nextSlotChange;
    private long axeReadyAt;
    private long digAt;
    private long digSince;
    private long lastSwing;
    private long idleSince;
    private String pendingCommand;
    private long sendPendingAt;
    private long lastSwitchAt;
    private long settleUntil;
    private boolean refreshing;
    /** Lobby swaps in a row that ended without a spot being mined in between; and whether the running swap is the hub routine. */
    private int refreshStreak;
    private boolean inHub;
    private int refreshesInARow;
    private long scootStart;
    private long scootProgressAt;
    private double scootBest;
    private final Map<BlockPos, Long> scootBlocked = new HashMap<BlockPos, Long>();
    private final Map<BlockPos, Long> nukeFirst = new HashMap<BlockPos, Long>();
    private final Map<BlockPos, Long> nukeLast = new HashMap<BlockPos, Long>();
    private int tickCount;
    private int lastNukeTick = -100;
    private int digTries;
    private long lastFloorDrop;
    private String lastPlanReport = "no plan yet";
    private String prepWhy = "";
    private Stage loggedStage = Stage.CHOOSE;
    private int seenResets;
    public long runStart;
    public long runMs;
    public int spotsDone;
    public int warps;
    public int floorDrops;
    public int shards;
    private Vec3 lastPos;
    private static final double WALLED_PENALTY = 25.0;
    private final Map<BlockPos, long[]> exposedCache = new HashMap<BlockPos, long[]>();

    private Macro() {
    }

    public boolean running() {
        return this.running;
    }

    public String status() {
        return this.status;
    }

    public Pathfinder.Goal goal() {
        return this.goal;
    }

    public List<Pathfinder.Step> steps() {
        return this.steps;
    }

    public int stepIdx() {
        return this.stepIdx;
    }

    private int avoidSwaps;

    public void avoidClear() {
        this.avoidSwaps = 0;
    }

    /** A listed player is in this lobby: swap lobby (the normal swap routine); gives up after 8 swaps in a row. */
    public void avoidSwap(Minecraft mc, String who) {
        if (!this.running || this.refreshing || this.warping || mc.player == null) {
            return;
        }
        if (++this.avoidSwaps > Config.giveUpAfter) {
            this.avoidSwaps = 0;
            this.stop(mc, who + " keeps showing up after " + Config.giveUpAfter + " lobby swaps");
            return;
        }
        Chat.msg(who + " is in this lobby - swapping lobby (" + this.avoidSwaps + "/" + Config.giveUpAfter + ").");
        this.startSwap(mc, mc.player, System.currentTimeMillis());
    }

    public void start(Minecraft mc) {
        if (this.running || mc.player == null) {
            return;
        }
        if (Macro.aotvSlot(mc.player) < 0) {
            Chat.msg("Put an Aspect of the Void with Ether Transmission in your hotbar first.");
            return;
        }
        if (Config.canyon && !Config.canyonSet()) {
            int r = 60;
            Config.canyonMinX = mc.player.getBlockX() - r;
            Config.canyonMaxX = mc.player.getBlockX() + r;
            Config.canyonMinZ = mc.player.getBlockZ() - r;
            Config.canyonMaxZ = mc.player.getBlockZ() + r;
            Config.save();
            Chat.msg("Torrhus Canyon mode: no canyon patrol area was set, using a 120 x 120 box around you (change it in the Lobby tab).");
        }
        this.running = true;
        this.startLevel = mc.level;
        this.stage = Stage.CHOOSE;
        this.runStart = System.currentTimeMillis();
        this.lastPos = mc.player.position();
        this.skipUntil.clear();
        this.planFails.clear();
        this.avoid.clear();
        this.warpFails = 0;
        this.refreshStreak = 0;
        this.inHub = false;
        this.warpsSinceSpot = 0;
        this.warping = false;
        this.deferCmd = null;
        this.resumeAt = 0L;
        this.moveRef = null;
        this.aim.reset();
        this.aim.retarget();
        Stats.begin();
        Chat.msg("Started. Hypixel bans macros - you use this at your own risk.");
    }

    public void stop(Minecraft mc, String why) {
        if (!this.running) {
            return;
        }
        this.running = false;
        this.runMs += System.currentTimeMillis() - this.runStart;
        Macro.release(mc);
        this.cancelPlan();
        this.steps = new ArrayList<Pathfinder.Step>();
        this.goal = null;
        this.status = "Stopped";
        this.aim.loose();
        Chat.msg("Stopped" + (String)(why == null ? "." : ": " + why));
    }

    public void toggle(Minecraft mc) {
        if (this.running) {
            this.stop(mc, null);
        } else {
            this.start(mc);
        }
    }

    /** Sets the HUD numbers back to zero (time, spots, drops, shards, etherwarps). A running macro keeps running. */
    public void resetStats() {
        this.spotsDone = 0;
        this.warps = 0;
        this.floorDrops = 0;
        this.shards = 0;
        this.runMs = 0L;
        this.runStart = System.currentTimeMillis();
        Stats.resetCurrent();
    }

    public long totalMs() {
        return this.runMs + (this.running ? System.currentTimeMillis() - this.runStart : 0L);
    }

    public void onChat(String raw) {
        String s = raw.replaceAll("\u00a7.", "");
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("enough mana")) {
            this.noManaUntil = System.currentTimeMillis() + 3000L;
        }
        if (this.running && this.warping && this.deferCmd == null && this.lastCmdText != null
                && (lower.contains("couldn't warp you") || lower.contains("player_transfer_cooldown")) && ++this.cmdRetries <= 3) {
            long now = System.currentTimeMillis(); // the server refused: send it again after the cooldown
            this.deferCmd = this.lastCmdText;
            this.deferAt = now + (long)Math.max(Config.warpCooldownSeconds, 3) * 1000L;
            this.warpWaitUntil = this.deferAt + 15000L;
            Chat.msg("The server refused the warp (transfer cooldown) - trying /" + this.deferCmd + " again in " + Math.max(Config.warpCooldownSeconds, 3) + " s.");
        }
        if (this.running && s.contains("There are blocks in the way")) {
            this.blockedAt = System.currentTimeMillis();
        }
        Stats.chat(s);
        if (s.contains("FLOOR DROP!")) {
            ++this.floorDrops;
            this.lastFloorDrop = System.currentTimeMillis();
            if (lower.contains("mudworm")) {
                ++this.shards;
            }
        }
    }

    public void tick(Minecraft mc) {
        if (!this.running) {
            return;
        }
        ++this.tickCount;
        long now = System.currentTimeMillis();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            if (this.warping) {
                return;
            }
            this.stop(mc, "left the world");
            return;
        }
        if (mc.level != this.startLevel) {
            if (this.warping) {
                this.startLevel = mc.level;
            } else {
                this.stop(mc, "the world changed (warp / lobby swap)");
                return;
            }
        }
        this.cache.tick(mc, 3000000L);
        if (this.seenResets != SpotScanner.resets) {
            this.seenResets = SpotScanner.resets;
            this.skipUntil.clear();
            this.planFails.clear();
            this.nukeFirst.clear();
            this.nukeLast.clear();
            this.scootBlocked.clear();
            this.patrolVisited.clear();
            this.avoid.clear();
            this.lastSwitchAt = now;
            this.settleUntil = now + (long)Config.settleSeconds * 1000L;
        }
        Vec3 pos = p.position();
        if (this.warping) {
            boolean moved;
            this.status = "Warping";
            Macro.release(mc);
            if (this.deferCmd != null) {
                this.status = "Warp cooldown: /" + this.deferCmd + " in " + Math.max(0L, (this.deferAt - now) / 1000L + 1L) + " s";
                if (now >= this.deferAt) {
                    p.connection.sendCommand(this.deferCmd);
                    this.lastCmdAt = now;
                    this.deferCmd = null;
                    this.warpWaitUntil = now + 15000L;
                }
                return;
            }
            boolean bl = moved = pos.distanceTo(this.warpPos) > 6.0 || mc.level != this.warpLevel;
            if (moved || now > this.warpWaitUntil) {
                this.warping = false;
                this.resumeAt = now + (long)(moved ? 2000 : 500);
                this.afterTeleport(pos, now);
                this.settleUntil = now + (long)Config.settleSeconds * 1000L;
                if (this.pendingCommand != null) {
                    this.sendPendingAt = now + (long)Config.swapGapMs;
                } else if (this.refreshing) {
                    this.refreshing = false;
                    this.lastSwitchAt = now;
                    if (this.inHub) {
                        this.inHub = false;
                        this.refreshStreak = 0;
                    } else {
                        ++this.refreshStreak;
                    }
                    Chat.msg("Lobby refreshed.");
                }
            }
            return;
        }
        if (now < this.resumeAt) {
            this.status = "Settling";
            Macro.release(mc);
            this.lastPos = pos;
            return;
        }
        if (this.pendingCommand != null) {
            this.status = "Refreshing lobby: /" + this.pendingCommand + " in " + Math.max(0L, (this.sendPendingAt - now) / 1000L + 1L) + " s";
            Macro.release(mc);
            this.lastPos = pos;
            if (now >= this.sendPendingAt) {
                String c = this.pendingCommand;
                this.pendingCommand = null;
                this.beginWarp(mc, p, now, c);
            }
            return;
        }
        if (this.lastPos != null && pos.distanceTo(this.lastPos) > 6.0 && this.stage != Stage.WARP_WAIT && Config.stopOnForeignTeleport) {
            this.lastPos = pos;
            this.stop(mc, "something teleported you (staff check?)");
            return;
        }
        this.lastPos = pos;
        if (mc.screen != null && !com.nablzz.addons.arcade.Arcade.isArcade(mc.screen)) {
            Macro.release(mc);
            this.status = "Paused (screen open)";
            return;
        }
        this.skipUntil.values().removeIf(t -> t < now);
        this.terrainWait = false;
        this.nukeNearby(mc, p, now);
        switch (this.stage.ordinal()) {
            case 0: {
                this.choose(mc, p, now);
                break;
            }
            case 1: {
                this.plan(mc, p, now);
                break;
            }
            case 2: {
                this.follow(mc, p, now);
                break;
            }
            case 3: {
                this.warpPrep(mc, p, now);
                break;
            }
            case 4: {
                this.warpWait(mc, p, now);
                break;
            }
            case 5: {
                this.dig(mc, p, now);
                break;
            }
            case 6: {
                this.digWait(mc, p, now);
                break;
            }
            case 7: {
                this.scoot(mc, p, now);
            }
        }
        if (this.stage != this.loggedStage) {
            Chat.log("stage " + String.valueOf((Object)this.loggedStage) + " -> " + String.valueOf((Object)this.stage) + " (" + this.status + ")");
            this.loggedStage = this.stage;
        }
        if (this.running && Config.unstuckWarp && this.stuck(p, now)) {
            this.stuckWarp(mc, p, now, "Did not move for " + Config.stuckSeconds + " s");
        }
    }

    private void afterTeleport(Vec3 pos, long now) {
        this.cancelPlan();
        this.steps = new ArrayList<Pathfinder.Step>();
        this.stepIdx = 0;
        this.goal = null;
        this.stage = Stage.CHOOSE;
        this.lastPos = pos;
        this.moveRef = pos;
        this.moveRefAt = now;
        this.avoid.clear();
        this.aim.reset();
        this.aim.retarget();
    }

    private boolean stuck(LocalPlayer p, long now) {
        boolean expectMove = !this.terrainWait && (this.stage == Stage.CHOOSE || this.stage == Stage.PLAN || this.stage == Stage.FOLLOW);
        Vec3 pos = p.position();
        if (!expectMove || this.moveRef == null || pos.distanceToSqr(this.moveRef) >= 1.0) {
            this.moveRef = pos;
            this.moveRefAt = now;
            return false;
        }
        return now - this.moveRefAt >= (long)Config.stuckSeconds * 1000L;
    }

    private void stuckWarp(Minecraft mc, LocalPlayer p, long now, String reason) {
        String cmd = Config.stuckCommandNow().trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        this.moveRefAt = now;
        if (cmd.isBlank()) {
            return;
        }
        if (++this.warpsSinceSpot > Config.stuckHubAfter) {
            this.warpsSinceSpot = 0;
            if (++this.refreshesInARow > Config.giveUpAfter) {
                this.stop(mc, "still stuck after " + Config.giveUpAfter + " lobby changes");
                return;
            }
            Chat.msg("Still stuck after /" + cmd + " " + Config.stuckHubAfter + " times - doing the hub routine: /hub, then /" + Config.hubSecondNow());
            this.inHub = true;
            this.refreshing = true;
            this.lastSwitchAt = now;
            this.pendingCommand = Config.hubSecondNow();
            this.beginWarp(mc, p, now, "hub");
            return;
        }
        Chat.msg(reason + " - /" + cmd + " (stage " + String.valueOf((Object)this.stage) + ", " + this.status + ")");
        this.beginWarp(mc, p, now, cmd);
    }

    private long lastCmdAt;
    private long deferAt;
    private String deferCmd;
    private String lastCmdText;
    private int cmdRetries;

    private void beginWarp(Minecraft mc, LocalPlayer p, long now, String cmd) {
        Macro.release(mc);
        this.cancelPlan();
        this.steps = new ArrayList<Pathfinder.Step>();
        this.goal = null;
        this.lastCmdText = cmd;
        this.cmdRetries = 0;
        long at = Math.max(now, this.lastCmdAt + (long)Config.warpCooldownSeconds * 1000L);
        if (at > now) {
            this.deferCmd = cmd;
            this.deferAt = at;
        } else {
            p.connection.sendCommand(cmd);
            this.lastCmdAt = now;
        }
        this.warping = true;
        this.warpPos = p.position();
        this.warpLevel = mc.level;
        this.warpWaitUntil = at + 15000L;
    }

    /**
     * The lobby swap. After {@code Config.hubAfter} refreshes in a row that found no spot to mine, the hub routine runs
     * instead (/hub, then /warp galatea), which gives a really new lobby.
     */
    private void startSwap(Minecraft mc, LocalPlayer p, long now) {
        this.refreshing = true;
        this.lastSwitchAt = now;
        if (Config.hubAfter > 0 && this.refreshStreak >= Config.hubAfter) {
            Chat.msg("The lobby was refreshed " + this.refreshStreak + " times in a row without a spot - doing the hub routine: /hub, then /" + Config.hubSecondNow());
            this.refreshStreak = 0;
            this.inHub = true;
            this.pendingCommand = Config.hubSecondNow();
            this.beginWarp(mc, p, now, "hub");
            return;
        }
        this.inHub = false;
        this.pendingCommand = Config.swapSecondNow().isBlank() ? null : Config.swapSecondNow();
        this.beginWarp(mc, p, now, Config.swapFirst.isBlank() ? "warp galatea" : Config.swapFirst);
    }

    private int liveSpots(ClientLevel lv) {
        int n = 0;
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            if (SpotScanner.BLACKLIST.contains(s) || !SpotScanner.alive(lv, s)) continue;
            ++n;
        }
        return n;
    }

    private boolean maybeRefreshLobby(Minecraft mc, LocalPlayer p, long now) {
        if (Config.minSpots <= 0 || now < this.settleUntil) {
            return false;
        }
        int n = this.liveSpots(mc.level);
        if (n >= Config.minSpots) {
            return false;
        }
        long wait = (long)Config.refreshCooldownSeconds * 1000L - (now - this.lastSwitchAt);
        if (wait > 0L) {
            Chat.debug("Only " + n + " spots (minimum " + Config.minSpots + ") but the lobby-switch cooldown has " + wait / 1000L + " s left.");
            return false;
        }
        if (++this.refreshesInARow > Config.giveUpAfter) {
            this.stop(mc, "the last " + Config.giveUpAfter + " lobbies all had fewer than " + Config.minSpots + " spots - lower /mudworm minspots");
            return true;
        }
        Chat.msg("Only " + n + " spots in this lobby (minimum " + Config.minSpots + ") - refreshing: /" + Config.swapFirst + ", then /" + Config.swapSecondNow());
        this.startSwap(mc, p, now);
        return true;
    }

    private void choose(Minecraft mc, LocalPlayer p, long now) {
        Macro.release(mc);
        this.aim.loose();
        this.replans = 0;
        this.routeRounds = 0;
        this.approaching = null;
        if (now < this.etherOffUntil) {
            this.terrainWait = true;
            this.status = "Etherwarp not working - waiting";
            return;
        }
        if (!this.cache.has(p.blockPosition())) {
            this.terrainWait = true;
            this.status = "Scanning terrain";
            return;
        }
        if (this.maybeRefreshLobby(mc, p, now)) {
            return;
        }
        BlockPos near = this.spotInReach(mc, p);
        if (near != null || this.nearestSpot(p) != null) {
            this.idleSince = 0L;
            this.idleStrikes = 0;
        }
        if (near != null) {
            this.goal = Pathfinder.Goal.spot(near);
            this.patrolPoint = null;
            this.enterDig(now);
            return;
        }
        BlockPos sc = this.scootCandidate(mc, p, now);
        if (sc != null) {
            this.goal = Pathfinder.Goal.spot(sc);
            this.patrolPoint = null;
            this.startScoot(mc, now);
            return;
        }
        BlockPos spot = this.nearestSpot(p);
        if (spot != null) {
            this.goal = Pathfinder.Goal.spot(spot);
            this.patrolPoint = null;
            this.beginPlan(mc, p, now);
            return;
        }
        this.terrainWait = true;
        this.status = "Waiting for a mudworm spot";
        this.goal = null;
        this.patrolPoint = null;
        if (this.idleSince == 0L) {
            this.idleSince = now;
        }
        if (now >= this.settleUntil && Config.noSpotSeconds > 0 && now - this.idleSince > (long)Config.noSpotSeconds * 1000L) {
            this.idleSince = 0L;
            if (++this.idleStrikes >= 1) {
                this.idleStrikes = 0;
                Chat.msg("No spot in view for " + Config.noSpotSeconds + " s - swapping lobby: /" + Config.swapFirst + ", then /" + Config.swapSecondNow());
                this.startSwap(mc, p, now);
                return;
            }
            this.stuckWarp(mc, p, now, "No spot in view and not moving for " + Config.stuckSeconds + " s");
        }
    }

    private Pathfinder.Limits limitsFor(LocalPlayer p, Pathfinder.Goal g) {
        double gy = g.center().y;
        double py = p.getY();
        int m = Config.areaMargin;
        this.lastLimits = new Pathfinder.Limits((int)Math.floor(Math.min(py, gy)) - Config.landBelow, (int)Math.ceil(Math.max(py, gy)) + Config.landAbove, (int)Math.floor(Math.min(p.getX(), g.center().x)) - m, (int)Math.ceil(Math.max(p.getX(), g.center().x)) + m, (int)Math.floor(Math.min(p.getZ(), g.center().z)) - m, (int)Math.ceil(Math.max(p.getZ(), g.center().z)) + m);
        return this.lastLimits;
    }

    private BlockPos spotInReach(Minecraft mc, LocalPlayer p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        Vec3 eye = p.getEyePosition();
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            double d;
            if (this.skipUntil.containsKey(s) || SpotScanner.BLACKLIST.contains(s) || (d = s.getCenter().distanceToSqr(eye)) > (Config.reach + 0.5) * (Config.reach + 0.5) || d >= bd || Macro.clickPoint((Level)mc.level, p, eye, s) == null) continue;
            bd = d;
            best = s;
        }
        return best;
    }

    public boolean isExposed(BlockPos s) {
        LocalPlayer pl = Minecraft.getInstance().player;
        if (pl != null && pl.getEyePosition().distanceToSqr(s.getCenter()) <= Config.reach * Config.reach) {
            return true;
        }
        long now = System.currentTimeMillis();
        long[] c = this.exposedCache.get(s);
        if (c != null && now - c[0] < 8000L) {
            return c[1] == 1L;
        }
        boolean r = this.computeExposed(s);
        this.exposedCache.put(s, new long[]{now, r ? 1L : 0L});
        return r;
    }

    private boolean computeExposed(BlockPos s) {
        WorldCache.Reader rd = this.cache.reader();
        double reach = Config.reach - 0.3;
        int rad = (int)Math.ceil(reach) + 1;
        boolean sawChunk = false;
        Vec3[] aims = new Vec3[]{new Vec3((double)s.getX() + 0.5, (double)s.getY() + 0.95, (double)s.getZ() + 0.5), s.getCenter()};
        for (int cx = s.getX() - rad >> 4; cx <= s.getX() + rad >> 4; ++cx) {
            for (int cz = s.getZ() - rad >> 4; cz <= s.getZ() + rad >> 4; ++cz) {
                long[] ts = rd.targets(cx, cz);
                if (ts == null) continue;
                sawChunk = true;
                for (long t : ts) {
                    Vec3 eye;
                    int tx = BlockPos.getX((long)t);
                    int ty = BlockPos.getY((long)t);
                    int tz = BlockPos.getZ((long)t);
                    if (Math.abs(tx - s.getX()) > rad || Math.abs(tz - s.getZ()) > rad || Math.abs(ty - s.getY()) > rad || (eye = new Vec3((double)tx + 0.5, (double)ty + 2.5, (double)tz + 0.5)).distanceToSqr(s.getCenter()) > reach * reach) continue;
                    for (Vec3 a : aims) {
                        if (!s.equals((Object)Etherwarp.firstSolid(rd, eye, a.subtract(eye).normalize(), eye.distanceTo(a) + 0.5))) continue;
                        return true;
                    }
                }
            }
        }
        return !sawChunk;
    }

    private BlockPos nearestSpot(LocalPlayer p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        ClientLevel lv = Minecraft.getInstance().level;
        if (lv == null) {
            return null;
        }
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            double d;
            if (this.skipUntil.containsKey(s) || SpotScanner.BLACKLIST.contains(s) || !SpotScanner.alive(lv, s) || (d = Math.sqrt(s.distToCenterSqr((Position)p.position()))) > Config.maxTargetDistance || Math.abs((double)s.getY() - p.getY()) > Config.maxTargetHeight) continue;
            if (!this.isExposed(s)) {
                d += 25.0;
            }
            if (!(d < bd)) continue;
            bd = d;
            best = s;
        }
        return best;
    }

    private boolean nearerSpotNow(LocalPlayer p, long now) {
        double dCur;
        BlockPos best = this.nearestSpot(p);
        if (best == null) {
            return false;
        }
        if (this.goal == null || this.goal.spot() == null) {
            return true;
        }
        if (best.equals((Object)this.goal.spot())) {
            return false;
        }
        double dBest = Math.sqrt(best.distToCenterSqr((Position)p.position()));
        if (dBest + 1.0 >= (dCur = Math.sqrt(this.goal.spot().distToCenterSqr((Position)p.position())))) {
            return false;
        }
        Chat.debug("Nearest spot is now " + best.toShortString() + " (" + (int)dBest + " blocks) instead of " + this.goal.spot().toShortString() + " (" + (int)dCur + ") - switching.");
        return true;
    }

    private BlockPos nextPatrolPoint(LocalPlayer p, long now) {
        Iterator<Map.Entry<BlockPos, Long>> it = this.patrolVisited.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() <= 120000L) continue;
            it.remove();
        }
        ArrayList<BlockPos> pts = new ArrayList<BlockPos>();
        for (BlockPos s : SpotScanner.SITES) {
            pts.add(s.above());
        }
        if (pts.isEmpty() || this.allVisited(pts)) {
            int y = p.blockPosition().getY();
            for (int x = Config.pMinX(); x <= Config.pMaxX(); x += Config.patrolStep) {
                for (int z = Config.pMinZ(); z <= Config.pMaxZ(); z += Config.patrolStep) {
                    pts.add(new BlockPos(x, y, z));
                }
            }
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos b : pts) {
            if (this.patrolVisited.containsKey(b) || this.skipUntil.containsKey(b)) continue;
            double d = Math.hypot((double)b.getX() + 0.5 - p.getX(), (double)b.getZ() + 0.5 - p.getZ());
            if (d < 10.0) {
                this.patrolVisited.put(b, now);
                continue;
            }
            if (!(d < bd)) continue;
            bd = d;
            best = b;
        }
        if (best == null && !this.patrolVisited.isEmpty()) {
            this.patrolVisited.clear();
        }
        return best;
    }

    private boolean allVisited(List<BlockPos> pts) {
        for (BlockPos b : pts) {
            if (this.patrolVisited.containsKey(b) || this.skipUntil.containsKey(b)) continue;
            return false;
        }
        return true;
    }

    private void beginPlan(Minecraft mc, LocalPlayer p, long now) {
        this.cancelPlan();
        int slot = Macro.aotvSlot(p);
        if (slot < 0) {
            this.stop(mc, "no Aspect of the Void with Ether Transmission in the hotbar");
            return;
        }
        double range = Etherwarp.range(p.getInventory().getItem(slot));
        Vec3 eye = new Vec3(p.getX(), p.getY() + 1.27, p.getZ());
        Chat.log("Planning from " + p.blockPosition().toShortString() + " to " + this.describeGoal() + ", range " + String.format("%.1f", range));
        this.planner = new Pathfinder(this.cache, p.blockPosition(), eye, this.goal, range, new HashSet<Long>(this.avoid), this.limitsFor(p, this.goal), p.getYRot(), p.getXRot(), Macro.aimStep(mc));
        this.exec.execute(this.planner::run);
        this.planStart = now;
        this.stage = Stage.PLAN;
        this.status = this.goal.spot() != null ? "Planning to spot" : "Planning patrol";
    }

    private static float aimStep(Minecraft mc) {
        double f = (Double)mc.options.sensitivity().get() * 0.6 + 0.2;
        return (float)(f * f * f * 8.0) * 0.15f;
    }

    private void cancelPlan() {
        if (this.planner != null) {
            this.planner.cancel();
        }
        this.planner = null;
    }

    private void plan(Minecraft mc, LocalPlayer p, long now) {
        Macro.release(mc);
        if (this.goalGone()) {
            this.cancelPlan();
            this.stage = Stage.CHOOSE;
            return;
        }
        this.faceGoalLoosely(p);
        Pathfinder.State st = this.planner.state();
        if (st == Pathfinder.State.RUNNING && now - this.planStart < 6000L) {
            return;
        }
        this.lastPlanReport = this.planner.report() + " | goal " + this.describeGoal() + " | " + (now - this.planStart) + " ms wall";
        Chat.log("Plan: " + this.lastPlanReport);
        if (st == Pathfinder.State.FOUND) {
            this.steps = this.planner.result();
            this.stepIdx = 0;
            this.planner = null;
            Chat.debug("Path: " + this.steps.size() + " etherwarps (" + (now - this.planStart) + " ms)");
            this.stage = Stage.FOLLOW;
            return;
        }
        this.cancelPlan();
        Chat.debug("No way to " + this.describeGoal() + " - going for the next one.");
        this.skipGoal(now);
        this.stage = Stage.CHOOSE;
    }

    private void replan(Minecraft mc, LocalPlayer p, long now, String why) {
        Chat.debug("Re-planning: " + why);
        Macro.release(mc);
        this.aim.loose();
        if (++this.replans > 4) {
            this.skipGoal(now);
            this.stage = Stage.CHOOSE;
            return;
        }
        this.beginPlan(mc, p, now);
    }

    private void skipGoal(long now) {
        if (this.goal == null) {
            return;
        }
        if (this.goal.spot() != null) {
            BlockPos sp = this.goal.spot();
            int fails = this.planFails.merge(sp, 1, Integer::sum);
            if (fails >= 2) {
                SpotScanner.BLACKLIST.add(sp);
                this.nukeFirst.remove(sp);
                this.nukeLast.remove(sp);
                Chat.msg("No way to the spot at " + sp.toShortString() + " (2 tries) - blacklisted until the lobby changes.");
            } else {
                this.skipUntil.put(sp, now + 45000L);
                Chat.debug("No way to " + sp.toShortString() + " from here - paused for 45 s, going for the next one.");
            }
        }
        if (this.patrolPoint != null) {
            this.skipUntil.put(this.patrolPoint, now + 60000L);
        }
    }

    private boolean goalGone() {
        return this.goal != null && this.goal.spot() != null && (!SpotScanner.SPOTS.containsKey(this.goal.spot()) || SpotScanner.BLACKLIST.contains(this.goal.spot()));
    }

    private void follow(Minecraft mc, LocalPlayer p, long now) {
        if (this.goalGone()) {
            Macro.release(mc);
            this.stage = Stage.CHOOSE;
            return;
        }
        BlockPos inReach = this.spotInReach(mc, p);
        if (inReach != null) {
            if (this.goal.spot() == null || !this.goal.spot().equals((Object)inReach)) {
                Chat.debug("Spot " + inReach.toShortString() + " is already in reach - taking it without more hops.");
                this.goal = Pathfinder.Goal.spot(inReach);
                this.patrolPoint = null;
                this.approaching = null;
            }
            Macro.release(mc);
            this.enterDig(now);
            return;
        }
        BlockPos sc = this.scootCandidate(mc, p, now);
        if (sc != null) {
            this.goal = Pathfinder.Goal.spot(sc);
            this.patrolPoint = null;
            Macro.release(mc);
            this.startScoot(mc, now);
            return;
        }
        if (this.nearerSpotNow(p, now)) {
            Macro.release(mc);
            this.stage = Stage.CHOOSE;
            return;
        }
        List<Pathfinder.Step> list = this.steps;
        if (this.stepIdx >= list.size()) {
            Macro.release(mc);
            if (this.goal.spot() != null) {
                if (Macro.clickPoint((Level)mc.level, p, p.getEyePosition(), this.goal.spot()) != null) {
                    this.enterDig(now);
                } else if (++this.routeRounds > 6) {
                    Chat.debug("Still not in reach after 6 routes - skipping this spot for a minute.");
                    this.skipGoal(now);
                    this.stage = Stage.CHOOSE;
                } else {
                    this.beginPlan(mc, p, now);
                }
            } else {
                if (this.patrolPoint != null) {
                    this.patrolVisited.put(this.patrolPoint, now);
                }
                this.stage = Stage.CHOOSE;
            }
            return;
        }
        this.shot = list.get(this.stepIdx).shot();
        this.prepSince = now;
        if (!p.isShiftKeyDown()) {
            this.sneakSince = 0L;
        }
        this.aim.precise();
        this.readySince = 0L;
        this.stage = Stage.WARP_PREP;
    }

    private void warpPrep(Minecraft mc, LocalPlayer p, long now) {
        float pitch;
        float yaw;
        this.status = "Etherwarp: aiming";
        Macro.stopMoving(mc);
        int slot = Macro.aotvSlot(p);
        if (slot < 0) {
            this.stop(mc, "no Aspect of the Void with Ether Transmission in the hotbar");
            return;
        }
        if (p.getInventory().getSelectedSlot() != slot) {
            if (now >= this.nextSlotChange) {
                p.getInventory().setSelectedSlot(slot);
                this.nextSlotChange = now + 150L;
            }
            return;
        }
        mc.options.keyShift.setDown(true);
        if (p.isShiftKeyDown()) {
            if (this.sneakSince == 0L) {
                this.sneakSince = now;
            }
        } else {
            this.sneakSince = 0L;
        }
        if (Config.blatant) {
            this.aim.precise();
            this.aim.setTarget(p.getYRot(), p.getXRot(), 100.0f, 1.0f);
        } else {
            this.aim.setTarget(this.shot.yaw(), this.shot.pitch(), 2500.0f, 1.6f);
        }
        if (now - this.prepSince > (long)(3000 + Config.hopDelayMs)) {
            this.replan(mc, p, now, "could not line up the etherwarp");
            return;
        }
        if (now < this.noManaUntil) {
            this.status = "Etherwarp: waiting for mana";
            return;
        }
        boolean still = p.onGround() && p.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4;
        boolean sneakOk = this.sneakSince != 0L && now - this.sneakSince >= 200L && this.nextSlotChange <= now;
        float yawErr = Math.abs(Mth.wrapDegrees((float)(this.shot.yaw() - p.getYRot())));
        float pitchErr = Math.abs(this.shot.pitch() - p.getXRot());
        boolean aimed = Config.blatant || this.aim.settled(p, Math.max(0.1f, Macro.aimStep(mc) * 0.75f));
        this.prepWhy = (sneakOk ? "sneak ok" : "waiting for sneak") + ", " + (still ? "still" : "moving/airborne") + ", " + (Config.blatant ? "BLATANT (no head movement)" : (aimed ? "aimed" : String.format("aiming (err yaw %.2f pitch %.2f)", Float.valueOf(yawErr), Float.valueOf(pitchErr))));
        if (!(sneakOk && still && aimed)) {
            this.readySince = 0L;
            return;
        }
        if (this.readySince == 0L) {
            this.readySince = now;
        }
        if (now - this.readySince < (long)Config.hopDelayMs) {
            this.prepWhy = this.prepWhy + ", waiting " + ((long)Config.hopDelayMs - (now - this.readySince)) + " ms (hop delay)";
            return;
        }
        Cell.Live live = new Cell.Live((Level)mc.level);
        double range = Etherwarp.range(p.getInventory().getItem(slot));
        Vec3 eye = new Vec3(p.getX(), p.getY() + 1.27, p.getZ());
        BlockPos hit = Etherwarp.predict(live, eye, yaw = Config.blatant ? this.shot.yaw() : p.getYRot(), pitch = Config.blatant ? this.shot.pitch() : p.getXRot(), range);
        if (hit != null && hit.distSqr((Vec3i)this.shot.target()) <= 100.0 && Etherwarp.coneSafe(live, eye, yaw, pitch, range)) {
            this.warpFrom = p.position();
            this.warpAt = now;
            if (Config.blatant) {
                float oy = p.getYRot();
                float op = p.getXRot();
                p.setYRot(yaw);
                p.setXRot(pitch);
                mc.gameMode.useItem((Player)p, InteractionHand.MAIN_HAND);
                p.setYRot(oy);
                p.setXRot(op);
            } else {
                mc.gameMode.useItem((Player)p, InteractionHand.MAIN_HAND);
            }
            ++this.warps;
            Stats.warp();
            Chat.log("Etherwarp #" + this.warps + " click: aiming at " + this.shot.target().toShortString() + ", ray ends on " + hit.toShortString() + " from " + p.blockPosition().toShortString());
            this.stage = Stage.WARP_WAIT;
            this.status = "Etherwarp: teleporting";
            return;
        }
        Chat.log("Etherwarp refused: the ray ends on " + (hit == null ? "an invalid block / nothing" : hit.toShortString()) + " but the plan wanted " + this.shot.target().toShortString());
        this.replan(mc, p, now, "aim did not match the plan");
    }

    private void warpWait(Minecraft mc, LocalPlayer p, long now) {
        boolean refused;
        Macro.stopMoving(mc);
        mc.options.keyShift.setDown(true);
        if (p.position().distanceTo(this.warpFrom) > 1.5) {
            this.lastPos = p.position();
            BlockPos land = this.shot.landing();
            double off = Math.hypot((double)land.getX() + 0.5 - p.getX(), (double)land.getZ() + 0.5 - p.getZ());
            this.warpFails = 0;
            Chat.log("Teleported to " + p.blockPosition().toShortString() + " (planned " + land.toShortString() + ")");
            if (off > 1.5 || Math.abs(p.getY() - (double)land.getY()) > 1.5) {
                this.replans = 0;
                this.aim.precise();
                this.beginPlan(mc, p, now);
                return;
            }
            ++this.stepIdx;
            if (this.stepIdx >= this.steps.size()) {
                mc.options.keyShift.setDown(false);
                this.aim.loose();
            }
            this.stage = Stage.FOLLOW;
            return;
        }
        boolean bl = refused = this.blockedAt >= this.warpAt;
        if (refused || now - this.warpAt > 1500L) {
            Chat.log((String)(refused ? "Server said \"There are blocks in the way!\" for the hop to " + this.shot.target().toShortString() : "Etherwarp click did nothing for 1500 ms (mana? server refused?)"));
            mc.options.keyShift.setDown(false);
            this.avoid.add(this.shot.landing().asLong());
            if (++this.warpFails >= 3) {
                this.warpFails = 0;
                this.etherOffUntil = now + 10000L;
                Macro.release(mc);
                if (Config.unstuckWarp) {
                    this.stuckWarp(mc, p, now, "Etherwarp did nothing 3 times");
                    return;
                }
                Chat.msg("Etherwarp did nothing 3 times (mana? item?) - waiting 10 s.");
                this.stage = Stage.CHOOSE;
                return;
            }
            this.replan(mc, p, now, "no teleport happened");
        }
    }

    private void enterDig(long now) {
        this.stage = Stage.DIG;
        this.digTries = 0;
        this.digSince = now;
    }

    private boolean digTimedOut(Minecraft mc, long now) {
        if (now - this.digSince < 2000L) {
            return false;
        }
        BlockPos spot = this.goal.spot();
        SpotScanner.BLACKLIST.add(spot);
        this.nukeFirst.remove(spot);
        this.nukeLast.remove(spot);
        Chat.msg("Could not get the spot at " + spot.toShortString() + " within 2 s - blacklisted until the lobby changes.");
        Macro.release(mc);
        mc.gameMode.stopDestroyBlock();
        this.stage = Stage.CHOOSE;
        return true;
    }

    private BlockPos scootCandidate(Minecraft mc, LocalPlayer p, long now) {
        if (Config.scootRange <= 0.0) {
            return null;
        }
        ClientLevel lv = mc.level;
        if (lv == null) {
            return null;
        }
        Vec3 eye = p.getEyePosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            double hd;
            Long until;
            if (SpotScanner.BLACKLIST.contains(s) || !SpotScanner.alive(lv, s) || (until = this.scootBlocked.get(s)) != null && until > now || eye.distanceTo(s.getCenter()) <= Config.reach - 0.3 || (hd = Math.hypot((double)s.getX() + 0.5 - p.getX(), (double)s.getZ() + 0.5 - p.getZ())) > Config.scootRange || Math.abs((double)(s.getY() + 1) - p.getY()) > 2.5) continue;
            if (!this.scootPathSafe(p, s)) {
                this.scootBlocked.put(s, now + 4000L);
                continue;
            }
            if (!(hd < bd)) continue;
            bd = hd;
            best = s;
        }
        return best;
    }

    private boolean scootPathSafe(LocalPlayer p, BlockPos spot) {
        WorldCache.Reader rd = this.cache.reader();
        double dx = (double)spot.getX() + 0.5 - p.getX();
        double dz = (double)spot.getZ() + 0.5 - p.getZ();
        double hd = Math.hypot(dx, dz);
        double walk = Math.max(0.0, hd - (Config.reach - 0.6));
        int feetY = (int)Math.floor(p.getY() + 0.01);
        double px = -dz / Math.max(hd, 1.0E-6);
        double pz = dx / Math.max(hd, 1.0E-6);
        int n = (int)Math.ceil(walk / 0.4) + 1;
        for (int i = 0; i <= n; ++i) {
            double t = Math.min(walk, (double)i * 0.4) / Math.max(hd, 1.0E-6);
            for (double off : new double[]{-0.3, 0.0, 0.3}) {
                int x = (int)Math.floor(p.getX() + dx * t + px * off);
                int z = (int)Math.floor(p.getZ() + dz * t + pz * off);
                short below = rd.at(x, feetY - 1, z);
                short feet = rd.at(x, feetY, z);
                short head = rd.at(x, feetY + 1, z);
                if ((below & 0x10) == 0 || (below & 0x1C0) != 0) {
                    return false;
                }
                for (short f : new short[]{feet, head}) {
                    if ((f & 1) != 0 && (f & 0x190) == 0) continue;
                    return false;
                }
            }
        }
        return true;
    }

    private void restartStuckTimer(long now) {
        LocalPlayer pl = Minecraft.getInstance().player;
        this.moveRef = pl == null ? null : pl.position();
        this.moveRefAt = now;
    }

    private void startScoot(Minecraft mc, long now) {
        Macro.release(mc);
        this.aim.precise();
        this.stage = Stage.SCOOT;
        this.scootStart = now;
        this.scootProgressAt = now;
        this.scootBest = Double.MAX_VALUE;
        this.status = "Scooting to spot";
        this.restartStuckTimer(now);
        Chat.log("Scooting to " + this.goal.spot().toShortString() + " (within " + Config.scootRange + " blocks, max " + Config.scootSeconds + " s, no teleport)");
    }

    private void giveUpScoot(Minecraft mc, long now, String why) {
        Macro.stopMoving(mc);
        this.restartStuckTimer(now);
        if (this.goal != null && this.goal.spot() != null) {
            this.scootBlocked.put(this.goal.spot(), now + 10000L);
        }
        Chat.debug("Scoot stopped: " + why);
        this.aim.loose();
        this.stage = Stage.CHOOSE;
    }

    private void scoot(Minecraft mc, LocalPlayer p, long now) {
        double dz;
        BlockPos spot;
        BlockPos blockPos = spot = this.goal == null ? null : this.goal.spot();
        if (spot == null || this.goalGone()) {
            Macro.stopMoving(mc);
            this.stage = Stage.CHOOSE;
            return;
        }
        this.status = "Scooting to spot";
        this.aim.precise();
        this.aim.setTarget(p.getYRot(), p.getXRot(), 100.0f, 1.0f);
        if (Macro.clickPoint((Level)mc.level, p, p.getEyePosition(), spot) != null) {
            Macro.stopMoving(mc);
            this.restartStuckTimer(now);
            this.aim.loose();
            this.enterDig(now);
            return;
        }
        double dx = (double)spot.getX() + 0.5 - p.getX();
        double hd = Math.hypot(dx, dz = (double)spot.getZ() + 0.5 - p.getZ());
        if (hd > Config.scootRange + 1.5 || now - this.scootStart > (long)(Config.scootSeconds * 1000.0)) {
            this.giveUpScoot(mc, now, "scoot time is up (" + Config.scootSeconds + " s) / moved away");
            return;
        }
        if (hd < this.scootBest - 0.05) {
            this.scootBest = hd;
            this.scootProgressAt = now;
        } else if (now - this.scootProgressAt > 700L) {
            this.giveUpScoot(mc, now, "no progress (blocked?)");
            return;
        }
        double yaw = Math.toRadians(p.getYRot());
        double fwd = dx * -Math.sin(yaw) + dz * Math.cos(yaw);
        double rgt = dx * -Math.cos(yaw) + dz * -Math.sin(yaw);
        double dead = 0.3;
        mc.options.keyUp.setDown(fwd > dead);
        mc.options.keyDown.setDown(fwd < -dead);
        mc.options.keyRight.setDown(rgt > dead);
        mc.options.keyLeft.setDown(rgt < -dead);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    private void dig(Minecraft mc, LocalPlayer p, long now) {
        Macro.stopMoving(mc);
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
        BlockPos spot = this.goal.spot();
        if (spot == null || this.goalGone()) {
            this.stage = Stage.CHOOSE;
            return;
        }
        if (this.digTimedOut(mc, now)) {
            return;
        }
        if (Macro.clickPoint((Level)mc.level, p, p.getEyePosition(), spot) == null) {
            this.status = "Spot not in reach from here";
            this.replan(mc, p, now, "spot not in reach from here");
            return;
        }
        this.status = "Nuking spot";
    }

    private void nukeNearby(Minecraft mc, LocalPlayer p, long now) {
        int ax;
        ClientLevel lv = mc.level;
        if (lv == null || mc.gameMode == null || (mc.screen != null && !com.nablzz.addons.arcade.Arcade.isArcade(mc.screen))) {
            return;
        }
        for (BlockPos s : new ArrayList<BlockPos>(this.nukeFirst.keySet())) {
            boolean gone;
            boolean bl = gone = !SpotScanner.SPOTS.containsKey(s) || !SpotScanner.alive(lv, s) || lv.getBlockState(s).isAir();
            if (!gone) continue;
            this.nukeFirst.remove(s);
            this.nukeLast.remove(s);
            if (SpotScanner.SPOTS.containsKey(s)) {
                ++this.spotsDone;
            Stats.spot();
            this.refreshStreak = 0;
                this.warpsSinceSpot = 0;
                this.refreshesInARow = 0;
                Chat.log("Spot " + s.toShortString() + " collected");
                SpotScanner.remove(s);
            }
            this.skipUntil.put(s, now + 30000L);
            this.planFails.remove(s);
        }
        if (this.tickCount - this.lastNukeTick < 3) {
            return;
        }
        Vec3 eye = p.getEyePosition();
        BlockPos pick = null;
        double pickD = Double.MAX_VALUE;
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            double d;
            if (SpotScanner.BLACKLIST.contains(s) || (d = eye.distanceToSqr(s.getCenter())) > Config.reach * Config.reach || d >= pickD) continue;
            Long first = this.nukeFirst.get(s);
            if (first != null && now - first > 2000L) {
                SpotScanner.BLACKLIST.add(s);
                this.nukeFirst.remove(s);
                this.nukeLast.remove(s);
                Chat.msg("Spot " + s.toShortString() + " did not break within 2 s - blacklisted until the lobby changes.");
                continue;
            }
            Long last = this.nukeLast.get(s);
            if (last != null && now - last < 500L || !SpotScanner.alive(lv, s)) continue;
            pickD = d;
            pick = s;
        }
        if (pick == null) {
            return;
        }
        if (Config.useAxe && this.stage != Stage.WARP_PREP && this.stage != Stage.WARP_WAIT && (ax = Macro.slotByName(p, Config.axeName)) >= 0) {
            if (p.getInventory().getSelectedSlot() != ax) { // just swapped from the Aspect of the Void: give the server time to equip the axe
                p.getInventory().setSelectedSlot(ax);
                this.axeReadyAt = now + (long)Config.axeEquipMs;
                return;
            }
            if (now < this.axeReadyAt) {
                return;
            }
        }
        this.nukeFirst.putIfAbsent(pick, now);
        this.nukeLast.put(pick, now);
        mc.gameMode.startDestroyBlock(pick, Direction.UP);
        p.swing(InteractionHand.MAIN_HAND);
        this.lastNukeTick = this.tickCount;
        Chat.log("Nuking spot " + pick.toShortString() + " from " + p.blockPosition().toShortString() + (this.goal != null && pick.equals((Object)this.goal.spot()) ? " (target)" : " (other spot in reach)"));
    }

    private void digWait(Minecraft mc, LocalPlayer p, long now) {
        BlockPos spot = this.goal.spot();
        boolean strings = !SpotScanner.alive(mc.level, spot);
        boolean broken = mc.level.getBlockState(spot).isAir();
        if (strings || broken || this.lastFloorDrop >= this.digAt) {
            mc.gameMode.stopDestroyBlock();
            ++this.spotsDone;
            Stats.spot();
            this.refreshStreak = 0;
            this.warpsSinceSpot = 0;
            Chat.log("Spot " + spot.toShortString() + " collected (" + (broken ? "block broken" : (strings ? "strings gone" : "floor drop message")) + ", " + (now - this.digAt) + " ms)");
            this.planFails.remove(spot);
            SpotScanner.remove(spot);
            this.skipUntil.put(spot, now + 30000L);
            this.stage = Stage.CHOOSE;
            return;
        }
        if (this.digTimedOut(mc, now)) {
            return;
        }
        if (Config.useAxe && now < this.axeReadyAt) {
            this.status = "Equipping the axe";
            return;
        }
        mc.gameMode.continueDestroyBlock(spot, Direction.UP);
        if (now - this.lastSwing > 200L) {
            p.swing(InteractionHand.MAIN_HAND);
            this.lastSwing = now;
        }
        this.status = "Breaking spot block";
    }

    private static BlockHitResult clickPoint(Level level, LocalPlayer player, Vec3 eye, BlockPos spot) {
        if (eye.distanceToSqr(spot.getCenter()) > Config.reach * Config.reach) {
            return null;
        }
        return new BlockHitResult(spot.getCenter(), Direction.UP, spot, false);
    }

    public WorldCache cache() {
        return this.cache;
    }

    private String describeGoal() {
        if (this.goal == null) {
            return "none";
        }
        return this.goal.spot() != null ? "spot " + this.goal.spot().toShortString() : "area " + BlockPos.containing((Position)this.goal.center()).toShortString() + " r" + (int)this.goal.radius();
    }

    public String devState(Minecraft mc, long now) {
        StringBuilder b = new StringBuilder();
        b.append("running: ").append(this.running).append("   stage: ").append((Object)this.stage).append("   status: ").append(this.status).append('\n');
        b.append("hops limited to (last plan): ").append(this.lastLimits == null ? "none yet" : this.lastLimits).append('\n');
        b.append("goal: ").append(this.describeGoal()).append("   patrolPoint: ").append(this.patrolPoint == null ? "none" : this.patrolPoint.toShortString()).append('\n');
        b.append("planner: ").append((String)(this.planner == null ? "idle" : String.valueOf((Object)this.planner.state()) + " (" + this.planner.expansions() + " expansions)")).append('\n');
        b.append("last plan: ").append(this.lastPlanReport).append('\n');
        b.append("steps: ").append(this.steps.size()).append("   stepIdx: ").append(this.stepIdx).append('\n');
        int i = 0;
        for (Pathfinder.Step st : this.steps) {
            if (i++ >= 12) {
                b.append("  ...\n");
                break;
            }
            b.append("  ").append(i - 1).append(": land ").append(st.feet().toShortString()).append(" target ").append(st.shot().target().toShortString()).append(String.format(" yaw %.2f pitch %.2f", Float.valueOf(st.shot().yaw()), Float.valueOf(st.shot().pitch()))).append('\n');
        }
        b.append("warp prep: ").append(this.prepWhy).append("   warps: ").append(this.warps).append("   warpFails: ").append(this.warpFails).append("   noManaFor: ").append(Math.max(0L, this.noManaUntil - now)).append(" ms   etherPauseFor: ").append(Math.max(0L, this.etherOffUntil - now)).append(" ms\n");
        b.append("shot: ").append((String)(this.shot == null ? "none" : this.shot.target().toShortString() + String.format(" yaw %.2f pitch %.2f", Float.valueOf(this.shot.yaw()), Float.valueOf(this.shot.pitch())))).append('\n');
        b.append("sneakSince: ").append((String)(this.sneakSince == 0L ? "not sneaking" : now - this.sneakSince + " ms")).append("   terrainWait: ").append(this.terrainWait).append('\n');
        b.append("stuck: still for ").append(this.moveRef == null ? 0L : now - this.moveRefAt).append(" ms (warps at ").append(Config.stuckSeconds * 1000).append(" ms)   warping: ").append(this.warping).append("   warpsSinceSpot: ").append(this.warpsSinceSpot).append('\n');
        b.append("blacklisted spots: ").append(SpotScanner.BLACKLIST.size()).append((String)(SpotScanner.BLACKLIST.isEmpty() ? "" : " " + String.valueOf(SpotScanner.BLACKLIST))).append("   plan failures per spot: ").append(this.planFails).append('\n');
        b.append("axe: ").append(Config.useAxe ? "ON" : "off").append(" name '").append(Config.axeName).append("'").append((String)(Config.useAxe ? " (hotbar slot " + Macro.slotByName(Minecraft.getInstance().player, Config.axeName) + ")" : "")).append('\n');
        int far = 0;
        for (BlockPos sp : SpotScanner.SPOTS.keySet()) {
            LocalPlayer pl = mc.player;
            if (pl == null || !(Math.sqrt(sp.distToCenterSqr((Position)pl.position())) > Config.maxTargetDistance) && !(Math.abs((double)sp.getY() - pl.getY()) > Config.maxTargetHeight)) continue;
            ++far;
        }
        b.append("live spots in lobby: ").append(mc.level == null ? 0 : this.liveSpots(mc.level)).append(" (refresh below ").append(Config.minSpots).append(", cooldown ").append(Config.refreshCooldownSeconds).append(" s, last switch ").append((now - this.lastSwitchAt) / 1000L).append(" s ago, settling ").append(Math.max(0L, (this.settleUntil - now) / 1000L)).append(" s)   refreshing: ").append(this.refreshing).append("   refreshes in a row: ").append(this.refreshesInARow).append('\n');
        b.append("max target distance: ").append(Config.maxTargetDistance).append(" blocks, height ").append(Config.maxTargetHeight).append(" (").append(far).append(" known spots are farther and ignored)\n");
        b.append("scoot range: ").append(Config.scootRange).append(" blocks   scoot-blocked spots: ").append(this.scootBlocked.size()).append('\n');
        b.append("skipped spots: ").append(this.skipUntil.size()).append("   avoid cells: ").append(this.avoid.size()).append("   replans: ").append(this.replans).append('\n');
        b.append("done: ").append(this.spotsDone).append("   floor drops: ").append(this.floorDrops).append("   mudworm shards: ").append(this.shards).append('\n');
        return b.toString();
    }

    public String devPlanTest(Minecraft mc) {
        Pathfinder.Goal g;
        LocalPlayer p = mc.player;
        if (p == null) {
            return "no player";
        }
        int slot = Macro.aotvSlot(p);
        if (slot < 0) {
            return "cannot test: no Aspect of the Void with Ether Transmission in the hotbar";
        }
        BlockPos spot = this.nearestSpotIgnoringSkips(p);
        if (spot != null) {
            g = Pathfinder.Goal.spot(spot);
        } else {
            Vec3 me;
            Vec3 c = new Vec3((double)(Config.pMinX() + Config.pMaxX()) / 2.0, p.getY(), (double)(Config.pMinZ() + Config.pMaxZ()) / 2.0);
            if (c.distanceTo(me = p.position()) > 90.0) {
                c = me.add(c.subtract(me).normalize().scale(90.0));
            }
            g = Pathfinder.Goal.area(c, 10.0);
        }
        double range = Etherwarp.range(p.getInventory().getItem(slot));
        Vec3 eye = new Vec3(p.getX(), p.getY() + 1.27, p.getZ());
        Pathfinder pf = new Pathfinder(this.cache, p.blockPosition(), eye, g, range, new HashSet<Long>(), this.limitsFor(p, g), p.getYRot(), p.getXRot(), Macro.aimStep(mc));
        pf.run();
        StringBuilder b = new StringBuilder();
        b.append("test goal: ").append(g.spot() != null ? "nearest spot " + g.spot().toShortString() : "patrol area centre " + BlockPos.containing((Position)g.center()).toShortString()).append('\n');
        b.append("result: ").append(pf.report()).append('\n');
        int i = 0;
        for (Pathfinder.Step st : pf.result()) {
            b.append("  hop ").append(i++).append(": land ").append(st.feet().toShortString()).append(" target ").append(st.shot().target().toShortString()).append('\n');
        }
        return b.toString();
    }

    private BlockPos nearestSpotIgnoringSkips(LocalPlayer p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            double d;
            if (SpotScanner.BLACKLIST.contains(s) || !((d = s.distToCenterSqr((Position)p.position())) < bd)) continue;
            bd = d;
            best = s;
        }
        return best;
    }

    private void faceGoalLoosely(LocalPlayer p) {
        if (this.goal == null) {
            return;
        }
        float[] a = Etherwarp.angles(p.getEyePosition(), this.goal.center());
        this.aim.setTarget(a[0], 8.0f, 400.0f, 0.8f);
    }

    public static int slotByName(LocalPlayer p, String name) {
        if (name == null || name.isBlank()) {
            return -1;
        }
        String want = name.toLowerCase(Locale.ROOT).trim();
        int aotv = Macro.aotvSlot(p);
        for (int i = 0; i < 9; ++i) {
            ItemStack st;
            if (i == aotv || (st = p.getInventory().getItem(i)).isEmpty() || !st.getHoverName().getString().toLowerCase(Locale.ROOT).contains(want)) continue;
            return i;
        }
        return -1;
    }

    public static int aotvSlot(LocalPlayer p) {
        for (int i = 0; i < 9; ++i) {
            String name;
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty() || !(name = st.getHoverName().getString().toLowerCase(Locale.ROOT)).contains("aspect of the void") && !name.contains("aspect of the end") || !Etherwarp.hasEther(st)) continue;
            return i;
        }
        return -1;
    }

    private static void stopMoving(Minecraft mc) {
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump, mc.options.keySprint}) {
            k.setDown(false);
        }
    }

    private static void release(Minecraft mc) {
        Macro.stopMoving(mc);
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
    }

    static enum Stage {
        CHOOSE,
        PLAN,
        FOLLOW,
        WARP_PREP,
        WARP_WAIT,
        DIG,
        DIG_WAIT,
        SCOOT;

    }
}

