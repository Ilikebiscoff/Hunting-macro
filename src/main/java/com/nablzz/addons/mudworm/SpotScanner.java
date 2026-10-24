package com.nablzz.addons.mudworm;

import com.nablzz.addons.mudworm.Chat;
import java.io.IOException;
import java.lang.invoke.CallSite;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

public final class SpotScanner {
    private static final int STRINGS = 3;
    private static final double CONFIRM_RANGE = 40.0;
    private static final long GONE_MS = 4000L;
    private static final long FAR_FORGET_MS = 180000L;
    public static final Map<BlockPos, Spot> SPOTS = new LinkedHashMap<BlockPos, Spot>();
    public static final Set<BlockPos> BLACKLIST = new HashSet<BlockPos>();
    public static final Set<BlockPos> SITES = new HashSet<BlockPos>();
    private static final Path SITES_FILE = FabricLoader.getInstance().getConfigDir().resolve("mudworm-sites.txt");
    private static boolean sitesDirty;
    private static long lastScan;
    private static long lastSave;
    private static ClientLevel lastLevel;
    private static String lobbyId;
    private static long lastLobbyCheck;
    private static final Pattern SERVER_ID;
    public static volatile int resets;

    private SpotScanner() {
    }

    public static void onParticle(ClientboundLevelParticlesPacket packet) {
        if (packet.getParticle().getType() != ParticleTypes.HAPPY_VILLAGER) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        BlockPos pos = BlockPos.containing((double)packet.getX(), (double)(packet.getY() - 1.0), (double)packet.getZ());
        if (SpotScanner.countStrings(level, pos) >= 3) {
            SpotScanner.seen(pos, System.currentTimeMillis());
        }
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            if (!SPOTS.isEmpty()) {
                Chat.log("Spot list cleared: world object changed (blacklist kept until the lobby changes)");
            }
            SPOTS.clear();
            lastLevel = level;
        }
        if (level == null || mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLobbyCheck > 1000L) {
            lastLobbyCheck = now;
            SpotScanner.checkLobby(mc);
        }
        if (now - lastScan >= 500L) {
            lastScan = now;
            SpotScanner.scanEntities(level, now);
            SpotScanner.expire(mc, now);
        }
        if (sitesDirty && now - lastSave > 10000L) {
            SpotScanner.saveSites();
        }
    }

    private static void scanEntities(ClientLevel level, long now) {
        HashMap<BlockPos, Integer> count = new HashMap<BlockPos, Integer>();
        for (Entity entity : level.entitiesForRendering()) {
            Display.ItemDisplay d;
            if (!(entity instanceof Display.ItemDisplay) || !SpotScanner.isString(d = (Display.ItemDisplay)entity)) continue;
            count.merge(BlockPos.containing((double)d.getX(), (double)d.getY(), (double)d.getZ()), 1, Integer::sum);
        }
        for (Map.Entry entry : count.entrySet()) {
            if ((Integer)entry.getValue() < 3) continue;
            SpotScanner.seen((BlockPos)entry.getKey(), now);
        }
    }

    private static void expire(Minecraft mc, long now) {
        Vec3 me = mc.player.position();
        Iterator<Spot> it = SPOTS.values().iterator();
        while (it.hasNext()) {
            boolean near;
            Spot s = it.next();
            boolean bl = near = me.distanceTo(s.pos.getCenter()) < 40.0;
            if (near && now - s.lastSeen > 4000L) {
                it.remove();
                continue;
            }
            if (near || now - s.lastSeen <= 180000L) continue;
            it.remove();
        }
    }

    public static void reset(String why) {
        ++resets;
        if (!SPOTS.isEmpty() || !BLACKLIST.isEmpty()) {
            Chat.log("Spots and blacklist cleared: " + why);
        }
        SPOTS.clear();
        BLACKLIST.clear();
    }

    private static void checkLobby(Minecraft mc) {
        List<String> lines = SpotScanner.sidebar(mc);
        if (lines == null) {
            return;
        }
        for (String l : lines) {
            Matcher m = SERVER_ID.matcher(l);
            if (!m.find()) continue;
            String id = m.group(1);
            if (lobbyId != null && !lobbyId.equals(id)) {
                SpotScanner.reset("lobby " + lobbyId + " -> " + id);
            }
            lobbyId = id;
            return;
        }
    }

    public static List<String> sidebar(Minecraft mc) {
        if (mc.level == null) {
            return null;
        }
        Scoreboard sb = mc.level.getScoreboard();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) {
            return null;
        }
        ArrayList<String> out = new ArrayList<String>();
        for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
            if (e.isHidden()) continue;
            PlayerTeam team = sb.getPlayersTeam(e.owner());
            String line = team == null ? e.ownerName().getString() : PlayerTeam.formatNameForTeam((Team)team, (Component)e.ownerName()).getString();
            out.add(line.replaceAll("\u00a7.", "").trim());
        }
        return out;
    }

    private static BlockPos groundOf(ClientLevel level, BlockPos cube) {
        for (int i = 0; i <= 2; ++i) {
            BlockPos c = cube.below(i);
            if (level.getBlockState(c).getCollisionShape((BlockGetter)level, c).isEmpty()) continue;
            return c;
        }
        return null;
    }

    private static void seen(BlockPos cube, long now) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        BlockPos ground = SpotScanner.groundOf(level, cube);
        if (ground == null) {
            return;
        }
        Spot s = SPOTS.get(ground);
        if (s == null) {
            SPOTS.put(ground, new Spot(ground, cube, now));
            Chat.debug("Spot found at " + ground.toShortString() + (String)(ground.equals((Object)cube) ? "" : " (strings at " + cube.toShortString() + ")"));
        } else {
            s.lastSeen = now;
            s.cube = cube;
        }
        if (SITES.add(ground)) {
            sitesDirty = true;
        }
    }

    public static int countStrings(ClientLevel level, BlockPos pos) {
        List list = level.getEntitiesOfClass(Display.ItemDisplay.class, AABB.ofSize((Vec3)Vec3.atCenterOf((Vec3i)pos), (double)1.0, (double)1.0, (double)1.0), e -> true);
        int n = 0;
        for (Display.ItemDisplay d : list) {
            if (!SpotScanner.isString(d)) continue;
            ++n;
        }
        return n;
    }

    public static int stringsOf(ClientLevel level, BlockPos ground) {
        Spot s = SPOTS.get(ground);
        return SpotScanner.countStrings(level, s != null ? s.cube : ground);
    }

    public static boolean alive(ClientLevel level, BlockPos ground) {
        return SpotScanner.stringsOf(level, ground) >= 3;
    }

    private static boolean isString(Display.ItemDisplay d) {
        Display.ItemDisplay.ItemRenderState state = d.itemRenderState();
        return state != null && !state.itemStack().isEmpty() && state.itemStack().is((Object)Items.STRING);
    }

    public static void remove(BlockPos pos) {
        SPOTS.remove(pos);
    }

    public static void loadSites() {
        if (!Files.exists(SITES_FILE, new LinkOption[0])) {
            return;
        }
        try {
            for (String line : Files.readAllLines(SITES_FILE)) {
                String[] p = line.trim().split(",");
                if (p.length != 3) continue;
                try {
                    SITES.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                }
                catch (NumberFormatException numberFormatException) {}
            }
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    public static void saveSites() {
        sitesDirty = false;
        lastSave = System.currentTimeMillis();
        ArrayList<CallSite> lines = new ArrayList<CallSite>();
        for (BlockPos b : SITES) {
            lines.add((CallSite)((Object)(b.getX() + "," + b.getY() + "," + b.getZ())));
        }
        try {
            Files.write(SITES_FILE, lines, new OpenOption[0]);
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    public static void clearSites() {
        SITES.clear();
        SpotScanner.saveSites();
    }

    static {
        SERVER_ID = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{2,4}\\s+(\\S+)");
    }

    public static final class Spot {
        public final BlockPos pos;
        public BlockPos cube;
        public long lastSeen;

        Spot(BlockPos pos, BlockPos cube, long now) {
            this.pos = pos;
            this.cube = cube;
            this.lastSeen = now;
        }
    }
}

