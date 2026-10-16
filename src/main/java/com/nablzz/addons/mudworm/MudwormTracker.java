package com.nablzz.addons.mudworm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Galatea ground spots (the green sparkle particles over a block in the ground) from the particle packets
 * and remembers the block under each one.
 */
public final class MudwormTracker {
    private MudwormTracker() {}

    /** Registry ids that mark a spot (the green "+" sparkles are the happy villager particle). */
    public static volatile Set<String> markers = Set.of("minecraft:happy_villager");
    /** Only collect while the ESP or the macro is on. */
    public static volatile boolean enabled;
    /** While true every particle id is counted, so the right marker can be found (/mudworm scan). */
    public static volatile boolean scanning;

    private static final ConcurrentLinkedQueue<ClientboundLevelParticlesPacket> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Map<String, Integer> SEEN = new HashMap<>();
    /** Spots are re-checked this often: broken blocks go, and spots in view that stopped sparkling go. */
    private static final long RECHECK_MS = 5000;
    /** A spot this close with no particle for this long is gone (collected). Far spots are remembered. */
    private static final long GONE_MS = 8000;
    private static final double SEE_RANGE = 24;

    /** What we know about a spot: when a particle last confirmed it and which block it sits on. */
    public record Spot(long seen, Block block) {}

    /** Spot block -> info. Remembered for the whole island (until broken, collected or the lobby changes). */
    public static final Map<BlockPos, Spot> SPOTS = new ConcurrentHashMap<>();

    private static final Pattern SERVER_ID = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{2,4}\\s+(\\S+)");
    private static String lobbyId;
    private static ClientLevel lastLevel;
    private static long lastRecheck, lastLobbyCheck;
    /** How many times the ESP was reset (lobby / world change); shown in the settings. */
    public static volatile int resets;

    /** Called from the packet mixin (may be off the main thread). */
    public static void offer(ClientboundLevelParticlesPacket packet) {
        if ((enabled || scanning) && QUEUE.size() < 4000) QUEUE.add(packet);
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        long now = System.currentTimeMillis();
        if (level != lastLevel) { // new world (lobby swap, warp to another island): start over
            if (lastLevel != null) reset();
            lastLevel = level;
        }
        if (now - lastLobbyCheck > 1000) {
            lastLobbyCheck = now;
            checkLobby(mc);
        }
        ClientboundLevelParticlesPacket p;
        while ((p = QUEUE.poll()) != null) {
            if (level == null) continue;
            String name = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(p.getParticle().getType()));
            if (scanning) SEEN.merge(name, 1, Integer::sum);
            if (!markers.contains(name)) continue;
            BlockPos ground = ground(level, p.getX(), p.getY(), p.getZ());
            if (ground == null) continue;
            // the same spot sends particles over a few neighbouring cells: keep one entry per ~2 blocks
            BlockPos same = ground;
            for (BlockPos o : SPOTS.keySet()) {
                if (Math.abs(o.getX() - ground.getX()) <= 1 && Math.abs(o.getZ() - ground.getZ()) <= 1
                        && Math.abs(o.getY() - ground.getY()) <= 2) {
                    same = o;
                    break;
                }
            }
            SPOTS.put(same, new Spot(now, level.getBlockState(same).getBlock()));
        }
        if (level != null && now - lastRecheck >= RECHECK_MS) {
            lastRecheck = now;
            recheck(mc, level, now);
        }
    }

    /** Every 5 s: drop spots whose block was broken or changed, and spots in view that stopped sparkling. */
    private static void recheck(Minecraft mc, ClientLevel level, long now) {
        for (Iterator<Map.Entry<BlockPos, Spot>> it = SPOTS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            BlockPos bp = en.getKey();
            if (!level.isLoaded(bp)) continue; // out of loaded range: keep remembering it
            var state = level.getBlockState(bp);
            if (state.getCollisionShape(level, bp).isEmpty() || state.getBlock() != en.getValue().block()) {
                it.remove(); // broken / replaced
                continue;
            }
            boolean near = mc.player != null && mc.player.position().distanceToSqr(bp.getCenter()) < SEE_RANGE * SEE_RANGE;
            if (near && now - en.getValue().seen() > GONE_MS) it.remove();
        }
    }

    /** The lobby id is the short code next to the date on the sidebar (e.g. "10/14/26 m195H"). */
    private static void checkLobby(Minecraft mc) {
        var lines = com.nablzz.addons.mushroom.Failsafe.sidebar(mc);
        if (lines == null) return;
        for (String l : lines) {
            Matcher m = SERVER_ID.matcher(l);
            if (!m.find()) continue;
            String id = m.group(1);
            if (lobbyId != null && !lobbyId.equals(id)) reset();
            lobbyId = id;
            return;
        }
    }

    /** Forget every spot (lobby or world changed). They are found again from the particles within a few seconds. */
    public static void reset() {
        SPOTS.clear();
        QUEUE.clear();
        lastRecheck = System.currentTimeMillis();
        resets++;
    }

    public static void clear() {
        SPOTS.clear();
    }

    public static Map<String, Integer> drainSeen() {
        Map<String, Integer> copy = new TreeMap<>(SEEN);
        SEEN.clear();
        return copy;
    }

    /** The solid block at or just below the particle. */
    private static BlockPos ground(ClientLevel level, double x, double y, double z) {
        BlockPos b = BlockPos.containing(x, y, z);
        for (int i = 0; i <= 3; i++) {
            BlockPos c = b.below(i);
            if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) return c;
        }
        return null;
    }
}
