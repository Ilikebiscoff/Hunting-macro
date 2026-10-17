package com.nablzz.addons.mudworm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Path finder v4 (pure Java, no Minecraft classes, so it can be tested on its own). It looks at every block around
 * the walker: where there is ground, where a wall or a ledge is, hazards and water. Moves: walk, diagonal (only when
 * both corners are free), jump up one block, drop down to three blocks, jump a one-block gap, and (from the start only)
 * a teleport to any block the walker can see. Cells next to walls cost more, so the path keeps its distance from walls
 * and corners; the result is smoothed along straight, wall-free stretches.
 * The search is resumable: {@link Search#step} runs for a time budget, so it can be spread over several game ticks.
 */
final class Nav4 {
    private Nav4() {}

    static final int SOLID = 1, HAZARD = 2, WATER = 4, LAVA = 8, UNKNOWN = 16, LOW = 32, TALLX = 64;

    /** Block classes: bit set of the constants above. */
    interface World {
        int flags(int x, int y, int z);
    }

    /** Is this standing cell good enough to finish the search? */
    interface Goal {
        boolean reached(int x, int y, int z);
    }

    enum Kind { START, WALK, JUMP, FALL, GAP, ETHER }

    record Node(int x, int y, int z, Kind kind) {}

    record Result(List<Node> path, boolean complete, int expanded, double cost) {}

    static final class N {
        final int x, y, z;
        double g, f;
        N parent;
        Kind kind = Kind.WALK;
        boolean closed;

        N(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y + 2048 & 0xFFF);
    }

    static final class Search {
        private final World world;
        private final Goal goal;
        private final int tx, ty, tz;
        private final double slack;
        private final Set<Long> avoid;
        private final double etherBase;
        private final int maxExpand;
        private final Map<Long, Integer> cache = new HashMap<>();
        private final Map<Long, N> nodes = new HashMap<>();
        private final PriorityQueue<N> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        private final N start;
        private N best;
        private double bestH = Double.MAX_VALUE;
        private int expanded;
        private boolean done;
        private N found;
        private final List<int[]> ether;

        /**
         * @param tx,ty,tz target cell (heuristic only), {@code slack} how close counts as there (reach)
         * @param ether    teleport landing cells (feet position) reachable from the start, or empty
         * @param etherBase cost of a teleport, in blocks of walking
         */
        Search(World world, int sx, int sy, int sz, Goal goal, int tx, int ty, int tz, double slack,
               Set<Long> avoid, List<int[]> ether, double etherBase, int maxExpand) {
            this.world = world;
            this.goal = goal;
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            this.slack = slack;
            this.avoid = avoid;
            this.ether = ether;
            this.etherBase = etherBase;
            this.maxExpand = maxExpand;
            start = new N(sx, sy, sz);
            start.kind = Kind.START;
            start.f = h(sx, sy, sz);
            nodes.put(key(sx, sy, sz), start);
            open.add(start);
        }

        boolean done() {
            return done;
        }

        int expanded() {
            return expanded;
        }

        /** Runs until the budget (nanoseconds) is used up. True when the search has finished. */
        boolean step(long budgetNanos) {
            if (done) return true;
            long end = System.nanoTime() + budgetNanos;
            int n = 0;
            while (!open.isEmpty()) {
                if ((++n & 15) == 0 && System.nanoTime() > end) return false;
                N c = open.poll();
                if (c.closed) continue;
                c.closed = true;
                expanded++;
                double hh = h(c.x, c.y, c.z);
                if (hh < bestH) {
                    bestH = hh;
                    best = c;
                }
                if (goal.reached(c.x, c.y, c.z)) {
                    found = c;
                    done = true;
                    return true;
                }
                if (expanded >= maxExpand) break;
                expand(c);
            }
            done = true;
            return true;
        }

        Result result() {
            N end = found != null ? found : best;
            boolean complete = found != null;
            if (end == null || (!complete && end == start)) return new Result(List.of(), false, expanded, 0);
            if (!complete && h(start.x, start.y, start.z) - bestH < 2.0) return new Result(List.of(), false, expanded, 0); // no progress
            List<Node> out = new ArrayList<>();
            for (N c = end; c != null; c = c.parent) out.add(new Node(c.x, c.y, c.z, c.kind));
            Collections.reverse(out);
            return new Result(smooth(out), complete, expanded, end.g);
        }

        // ---- cells ---------------------------------------------------------------------------------------

        int fl(int x, int y, int z) {
            long k = key(x, y, z);
            Integer v = cache.get(k);
            if (v == null) {
                v = world.flags(x, y, z);
                cache.put(k, v);
            }
            return v;
        }

        boolean passable(int x, int y, int z) {
            return (fl(x, y, z) & (SOLID | HAZARD | LAVA | UNKNOWN)) == 0;
        }

        boolean ground(int x, int y, int z) {
            int f = fl(x, y, z);
            return (f & (SOLID | LOW)) != 0 && (f & (HAZARD | LAVA | UNKNOWN | TALLX)) == 0;
        }

        boolean standable(int x, int y, int z) {
            return ground(x, y - 1, z) && passable(x, y, z) && passable(x, y + 1, z);
        }

        private boolean wall(int x, int y, int z) {
            return (fl(x, y, z) & SOLID) != 0 || (fl(x, y + 1, z) & SOLID) != 0;
        }

        /** Extra cost of standing here: walls around (keeps the path off walls and corners), water. */
        private double around(int x, int y, int z) {
            int w = 0;
            if (wall(x + 1, y, z)) w++;
            if (wall(x - 1, y, z)) w++;
            if (wall(x, y, z + 1)) w++;
            if (wall(x, y, z - 1)) w++;
            double c = 0.3 * w;
            if ((fl(x, y, z) & WATER) != 0) c += 1.5;
            if (avoid != null && avoid.contains(key(x, y, z))) c += 30;
            return c;
        }

        private double h(int x, int y, int z) {
            double dx = Math.abs(x - tx), dz = Math.abs(z - tz);
            double oct = Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
            return 1.15 * (Math.max(0, oct - slack) + 0.8 * Math.abs(y - ty));
        }

        private void relax(N from, int x, int y, int z, double cost, Kind kind) {
            long k = key(x, y, z);
            N n = nodes.get(k);
            double g = from.g + cost;
            if (n == null) {
                n = new N(x, y, z);
                nodes.put(k, n);
            } else if (n.closed || n.g <= g) {
                return;
            }
            n.g = g;
            n.f = g + h(x, y, z);
            n.parent = from;
            n.kind = kind;
            open.add(n);
        }

        private static final int[][] CARD = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        private void expand(N c) {
            int x = c.x, y = c.y, z = c.z;
            boolean headroom = passable(x, y + 2, z);
            for (int[] d : CARD) {
                int nx = x + d[0], nz = z + d[1];
                if (standable(nx, y, nz)) {
                    relax(c, nx, y, nz, 1 + around(nx, y, nz), Kind.WALK);
                    continue;
                }
                // one block up
                if (headroom && standable(nx, y + 1, nz)) {
                    relax(c, nx, y + 1, nz, 1 + 2.0 + around(nx, y + 1, nz), Kind.JUMP);
                    continue;
                }
                if (!passable(nx, y, nz) || !passable(nx, y + 1, nz)) continue; // a wall: no way through
                // drop down (the cell in front is open air)
                boolean dropped = false;
                for (int k = 1; k <= 3 && !dropped; k++) {
                    if (!passable(nx, y - k + 1, nz)) break;
                    if (standable(nx, y - k, nz)) {
                        relax(c, nx, y - k, nz, 1 + 0.5 + 0.4 * k + around(nx, y - k, nz), Kind.FALL);
                        dropped = true;
                    }
                }
                if (dropped) continue;
                // jump a one-block gap: land two cells away at the same height
                int gx = x + 2 * d[0], gz = z + 2 * d[1];
                if (headroom && passable(nx, y + 2, nz) && standable(gx, y, gz) && passable(gx, y + 2, gz)) {
                    relax(c, gx, y, gz, 2 + 4.0 + around(gx, y, gz), Kind.GAP);
                }
            }
            for (int[] d : DIAG) {
                int nx = x + d[0], nz = z + d[1];
                if (!standable(nx, y, nz)) continue;
                // both corners must be open for the whole body, otherwise the walker clips the wall
                if (!passable(nx, y, z) || !passable(nx, y + 1, z) || !passable(x, y, nz) || !passable(x, y + 1, nz)) continue;
                if (!ground(nx, y - 1, z) || !ground(x, y - 1, nz)) continue; // no cutting over a hole
                relax(c, nx, y, nz, 1.414 + around(nx, y, nz), Kind.WALK);
            }
            if (c == start && ether != null) {
                for (int[] e : ether) {
                    double dd = Math.sqrt((e[0] - x) * (double) (e[0] - x) + (e[1] - y) * (double) (e[1] - y) + (e[2] - z) * (double) (e[2] - z));
                    relax(c, e[0], e[1], e[2], etherBase + 0.1 * dd + around(e[0], e[1], e[2]), Kind.ETHER);
                }
            }
        }

        // ---- smoothing -------------------------------------------------------------------------------------

        /** Walks a straight line (body width 0.6) at one height: every sample must be standable. */
        boolean line(int y, double x0, double z0, double x1, double z1) {
            double dx = x1 - x0, dz = z1 - z0, len = Math.hypot(dx, dz);
            if (len < 1e-6) return true;
            double px = -dz / len, pz = dx / len;
            int steps = (int) Math.ceil(len / 0.25);
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                for (double off : new double[]{-0.31, 0, 0.31}) {
                    int cx = (int) Math.floor(x0 + dx * t + px * off);
                    int cz = (int) Math.floor(z0 + dz * t + pz * off);
                    if (!standable(cx, y, cz)) return false;
                }
            }
            return true;
        }

        private List<Node> smooth(List<Node> in) {
            List<Node> out = new ArrayList<>();
            int i = 0;
            while (i < in.size()) {
                Node a = in.get(i);
                out.add(a);
                int far = i + 1;
                if (i + 1 < in.size() && in.get(i + 1).kind == Kind.WALK) {
                    for (int j = Math.min(in.size() - 1, i + 14); j > i + 1; j--) {
                        boolean ok = true;
                        for (int m = i + 1; m <= j && ok; m++) ok = in.get(m).y == a.y && in.get(m).kind == Kind.WALK;
                        if (ok && line(a.y, a.x + 0.5, a.z + 0.5, in.get(j).x + 0.5, in.get(j).z + 0.5)) {
                            far = j;
                            break;
                        }
                    }
                }
                i = far;
            }
            return out;
        }
    }
}
