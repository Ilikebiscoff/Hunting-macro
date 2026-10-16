package com.nablzz.addons.mudworm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    private static final long EXPIRE_MS = 12_000;
    private static final double SEE_RANGE = 28;

    /** Spot block -> last time a particle confirmed it. */
    public static final Map<BlockPos, Long> SPOTS = new ConcurrentHashMap<>();

    /** Called from the packet mixin (may be off the main thread). */
    public static void offer(ClientboundLevelParticlesPacket packet) {
        if ((enabled || scanning) && QUEUE.size() < 4000) QUEUE.add(packet);
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        ClientboundLevelParticlesPacket p;
        long now = System.currentTimeMillis();
        while ((p = QUEUE.poll()) != null) {
            if (level == null) continue;
            String name = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(p.getParticle().getType()));
            if (scanning) SEEN.merge(name, 1, Integer::sum);
            if (!markers.contains(name)) continue;
            BlockPos ground = ground(level, p.getX(), p.getY(), p.getZ());
            if (ground == null) continue;
            // the same spot sends particles over a few neighbouring cells: keep one entry per ~2 blocks
            BlockPos same = null;
            for (BlockPos o : SPOTS.keySet()) {
                if (Math.abs(o.getX() - ground.getX()) <= 1 && Math.abs(o.getZ() - ground.getZ()) <= 1
                        && Math.abs(o.getY() - ground.getY()) <= 2) {
                    same = o;
                    break;
                }
            }
            SPOTS.put(same != null ? same : ground, now);
        }
        for (Iterator<Map.Entry<BlockPos, Long>> it = SPOTS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (level == null) {
                it.remove();
                continue;
            }
            boolean near = mc.player != null && mc.player.position().distanceToSqr(en.getKey().getCenter()) < SEE_RANGE * SEE_RANGE;
            if (near && now - en.getValue() > EXPIRE_MS) it.remove();
            else if (!near && now - en.getValue() > 60_000) it.remove();
        }
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
