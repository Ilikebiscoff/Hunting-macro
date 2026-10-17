package com.nablzz.addons.mudworm;

import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the macro learned from its mistakes, kept between lobbies and game restarts (the island never changes):
 * <ul>
 *   <li>cells where it got stuck walking: the pathfinder avoids them from then on</li>
 *   <li>blocks a shift-teleport to failed: never picked as a landing again</li>
 *   <li>places where plain right-clicks toward a waypoint did not move it: walks there instead</li>
 * </ul>
 */
final class Learned {
    private static final int CAP = 3000;

    private final Set<BlockPos> avoid = new LinkedHashSet<>();
    private final Set<BlockPos> strike = new LinkedHashSet<>();
    private final Set<BlockPos> landStrike = new LinkedHashSet<>();
    private final Set<BlockPos> landing = new LinkedHashSet<>();
    private final Set<String> tp = new LinkedHashSet<>();
    private Path file;
    private boolean dirty;
    private long lastSave;

    synchronized void load(Path f) {
        file = f;
        if (!Files.isRegularFile(f)) return;
        try {
            for (String line : Files.readAllLines(f)) {
                String[] t = line.trim().split("\\s+");
                if (t.length == 4 && (t[0].equals("A") || t[0].equals("L"))) {
                    BlockPos b = new BlockPos(Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                    (t[0].equals("A") ? avoid : landing).add(b);
                } else if (t.length == 2 && t[0].equals("T")) {
                    tp.add(t[1]);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // start empty
        }
    }

    synchronized Set<BlockPos> avoid() {
        return new HashSet<>(avoid);
    }

    /** A cell is only avoided for good once it got us stuck twice (one stuck can be lag). */
    synchronized void addAvoid(BlockPos b) {
        if (!strike.add(b)) {
            if (add(avoid, b)) dirty = true;
        }
        trim(strike);
    }

    synchronized boolean badLanding(BlockPos b) {
        return landing.contains(b);
    }

    /** A landing is only banned once it failed twice (one failure can be lag or a click that came a moment too early). */
    synchronized void addBadLanding(BlockPos b) {
        if (!landStrike.add(b)) {
            if (add(landing, b)) dirty = true;
        }
        trim(landStrike);
    }

    private static String tpKey(BlockPos from, BlockPos goal) {
        return Math.floorDiv(from.getX(), 3) + "," + Math.floorDiv(from.getY(), 3) + "," + Math.floorDiv(from.getZ(), 3)
                + ">" + goal.getX() + "," + goal.getY() + "," + goal.getZ();
    }

    synchronized boolean badTp(BlockPos from, BlockPos goal) {
        return tp.contains(tpKey(from, goal));
    }

    synchronized void addBadTp(BlockPos from, BlockPos goal) {
        if (tp.add(tpKey(from, goal))) {
            dirty = true;
            trim(tp);
        }
    }

    private static <T> boolean add(Set<T> set, T v) {
        boolean added = set.add(v);
        if (added) trim(set);
        return added;
    }

    private static <T> void trim(Set<T> set) {
        while (set.size() > CAP) {
            Iterator<T> it = set.iterator();
            it.next();
            it.remove();
        }
    }

    synchronized String summary() {
        return avoid.size() + " blocked, " + landing.size() + " bad landings, " + tp.size() + " bad teleports";
    }

    synchronized void clear() {
        avoid.clear();
        landing.clear();
        tp.clear();
        dirty = true;
        save(true);
    }

    /** Writes the file if something changed (at most every 5 s unless forced). */
    synchronized void save(boolean force) {
        long now = System.currentTimeMillis();
        if (!dirty || file == null || (!force && now - lastSave < 5000)) return;
        List<String> lines = new ArrayList<>();
        for (BlockPos b : avoid) lines.add("A " + b.getX() + " " + b.getY() + " " + b.getZ());
        for (BlockPos b : landing) lines.add("L " + b.getX() + " " + b.getY() + " " + b.getZ());
        for (String k : tp) lines.add("T " + k);
        try {
            Files.write(file, lines);
            dirty = false;
        } catch (IOException ignored) {
            // try again later
        }
        lastSave = now;
    }
}
