package com.nablzz.addons.mudworm;

import com.nablzz.addons.minerzombie.HumanAim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a {@link Nav4} path. Before every jump it looks at the blocks around: room above the head at the take-off
 * cell, a free and solid-floored landing cell, nothing in the way for a gap jump. It never walks into a wall: the
 * cells ahead are read from the world each tick, and when the path no longer fits the world it reports BLOCKED so the
 * caller plans again.
 */
final class Walker {
    enum Status { WALKING, ARRIVED, BLOCKED }

    private List<Nav4.Node> path = List.of();
    private int idx = 1;
    private BlockPos blockedAt;
    private String blockedWhy = "";
    private Vec3 stuckFrom;
    private long stuckAt;
    private long jumpedAt, idxSince, wallSince;
    private int idxSeen = -1;

    boolean hasPath() {
        return path.size() >= 2;
    }

    void clear() {
        path = List.of();
        idx = 1;
        idxSeen = -1;
        stuckFrom = null;
    }

    /** Takes a new path; starts at the node nearest to where we stand (we moved while it was searched). */
    void set(List<Nav4.Node> nodes, Vec3 pos) {
        path = List.copyOf(nodes);
        stuckFrom = null;
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            Nav4.Node n = path.get(i);
            double d = new Vec3(n.x() + 0.5, n.y(), n.z() + 0.5).distanceToSqr(pos);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        idx = Math.max(1, best);
    }

    BlockPos blockedAt() {
        return blockedAt;
    }

    String blockedWhy() {
        return blockedWhy;
    }

    List<Vec3> points() {
        List<Vec3> out = new ArrayList<>();
        for (Nav4.Node n : path) out.add(new Vec3(n.x() + 0.5, n.y(), n.z() + 0.5));
        return out;
    }

    /** Length of the part of the path still to walk. */
    double remaining(LocalPlayer p) {
        if (!hasPath()) return 0;
        double sum = 0;
        Vec3 prev = p.position();
        for (int i = Math.min(idx, path.size() - 1); i < path.size(); i++) {
            Nav4.Node n = path.get(i);
            Vec3 v = new Vec3(n.x() + 0.5, n.y(), n.z() + 0.5);
            sum += v.distanceTo(prev);
            prev = v;
        }
        return sum;
    }

    // ---- reading the world ---------------------------------------------------------------------------------

    private static boolean passable(Nav4World w, int x, int y, int z) {
        return (w.flags(x, y, z) & (Nav4.SOLID | Nav4.HAZARD | Nav4.LAVA | Nav4.UNKNOWN)) == 0;
    }

    private static boolean ground(Nav4World w, int x, int y, int z) {
        int f = w.flags(x, y, z);
        return (f & (Nav4.SOLID | Nav4.LOW)) != 0 && (f & (Nav4.HAZARD | Nav4.LAVA | Nav4.UNKNOWN | Nav4.TALLX)) == 0;
    }

    private static boolean standable(Nav4World w, int x, int y, int z) {
        return ground(w, x, y - 1, z) && passable(w, x, y, z) && passable(w, x, y + 1, z);
    }

    private static boolean wall(Nav4World w, int x, int y, int z) {
        return (w.flags(x, y, z) & Nav4.SOLID) != 0 || (w.flags(x, y + 1, z) & Nav4.SOLID) != 0;
    }

    private Status block(int x, int y, int z, String why) {
        blockedAt = new BlockPos(x, y, z);
        blockedWhy = why;
        return Status.BLOCKED;
    }

    // ---- driving -------------------------------------------------------------------------------------------

    Status drive(Minecraft mc, LocalPlayer p, long now, HumanAim aim, boolean sprint) {
        if (!hasPath()) return Status.ARRIVED;
        Nav4World w = new Nav4World(mc.level);
        double px = p.getX(), pz = p.getZ();

        while (idx < path.size()) { // reached the node (or already past it)?
            Nav4.Node n = path.get(idx);
            Nav4.Node prev = path.get(idx - 1);
            double d = Math.hypot(n.x() + 0.5 - px, n.z() + 0.5 - pz);
            boolean level = Math.abs(n.y() - p.getY()) < 1.3;
            boolean last = idx == path.size() - 1;
            boolean plain = n.kind() == Nav4.Kind.WALK || n.kind() == Nav4.Kind.FALL;
            // passed the node: it lies behind us along the direction of travel (a turning circle must not trap us around it)
            boolean passed = plain && d < 2.0 && level
                    && (n.x() + 0.5 - px) * (n.x() - prev.x()) + (n.z() + 0.5 - pz) * (n.z() - prev.z()) <= 0;
            if (level && (d < (last ? 0.6 : plain ? 0.8 : 0.6) || (!last && passed))) {
                idx++;
                continue;
            }
            if (!last && p.onGround() && d < 1.2) { // cut the corner when the next node is already closer
                Nav4.Node nx = path.get(idx + 1);
                double d2 = Math.hypot(nx.x() + 0.5 - px, nx.z() + 0.5 - pz);
                boolean nplain = nx.kind() == Nav4.Kind.WALK || nx.kind() == Nav4.Kind.FALL;
                if (nplain && plain && d2 < d && Math.abs(nx.y() - p.getY()) < 1.3) {
                    idx++;
                    continue;
                }
            }
            break;
        }
        if (idx != idxSeen) { // the same node for 3 s while close to it: circling, move on
            idxSeen = idx;
            idxSince = now;
        } else if (now - idxSince > 3000 && idx < path.size()) {
            Nav4.Node n = path.get(idx);
            if (Math.hypot(n.x() + 0.5 - px, n.z() + 0.5 - pz) < 2.0 && Math.abs(n.y() - p.getY()) < 1.3) {
                idx++;
                idxSince = now;
            }
        }
        if (idx >= path.size()) return Status.ARRIVED;

        Nav4.Node t = path.get(idx);
        Nav4.Node from = path.get(idx - 1);
        double tx = t.x() + 0.5, tz = t.z() + 0.5;
        double dx = tx - px, dz = tz - pz, dist = Math.hypot(dx, dz);
        float yaw = dist < 0.2 ? p.getYRot() : (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        aim.setTarget(yaw, 8f, 520f, 1f);
        float err = Math.abs(Mth.wrapDegrees(yaw - p.getYRot()));
        boolean forward = err < 55f;
        boolean jump = false;
        boolean ground = p.onGround();

        // the world must still match the plan
        if (!standable(w, t.x(), t.y(), t.z())) return block(t.x(), t.y(), t.z(), "the next cell can't be stood on any more");

        switch (t.kind()) {
            case JUMP -> {
                // one block up: needs room above the head here and a free landing cell with a solid floor
                if (!passable(w, from.x(), from.y() + 2, from.z())) return block(from.x(), from.y() + 2, from.z(), "no room above to jump");
                if (!passable(w, t.x(), t.y() + 1, t.z())) return block(t.x(), t.y() + 1, t.z(), "no room to land");
                if (dist < 1.25) jump = ground;
                else forward = forward && err < 40f;
            }
            case GAP -> {
                int mx = (from.x() + t.x()) / 2, mz = (from.z() + t.z()) / 2;
                if (!passable(w, mx, from.y() + 2, mz) || !passable(w, from.x(), from.y() + 2, from.z()) || !passable(w, t.x(), t.y() + 2, t.z()))
                    return block(mx, from.y() + 2, mz, "no room above the gap");
                if (!passable(w, mx, from.y(), mz) || !passable(w, mx, from.y() + 1, mz)) return block(mx, from.y(), mz, "the gap is not open");
                if (dist < 2.1 && err < 15f && ground && sprint) jump = true; // sprint-jump from the edge of the take-off cell
                if (!sprint && dist < 2.1 && ground) jump = true;
            }
            default -> {
                // walking (or stepping down): feel for a wall right ahead with real collision rays (feet and head height)
                boolean wallNow = false;
                if (dist > 0.35 && err < 40f) {
                    double hx = dx / dist, hz = dz / dist;
                    double reach = Math.min(0.6, dist);
                    Vec3 dir = new Vec3(hx, 0, hz);
                    Vec3 f0 = new Vec3(px, p.getY() + 0.3, pz), f1 = new Vec3(px, p.getY() + 1.55, pz);
                    boolean lo = mc.level.clip(new ClipContext(f0, f0.add(dir.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.BLOCK;
                    boolean hi = mc.level.clip(new ClipContext(f1, f1.add(dir.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.BLOCK;
                    if (lo || hi) {
                        int ax = Mth.floor(px + hx * 0.8), az = Mth.floor(pz + hz * 0.8), cy = Mth.floor(p.getY() + 0.05);
                        // a single block we can hop onto (room above it and above our head)?
                        boolean hop = lo && !hi && passable(w, ax, cy + 1, az) && passable(w, ax, cy + 2, az)
                                && passable(w, Mth.floor(px), cy + 2, Mth.floor(pz));
                        if (hop) {
                            jump = ground;
                        } else {
                            wallNow = true;
                            forward = false;
                            if (wallSince == 0) wallSince = now;
                            if (now - wallSince > 900) { // it really is a wall (not a skimmed corner): plan around it
                                wallSince = 0;
                                return block(ax, cy, az, "wall ahead");
                            }
                        }
                    }
                }
                if (!wallNow) wallSince = 0;
            }
        }
        if (jump) jumpedAt = now;

        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(sprint && forward && err < 20f && dist > 2.5 && t.kind() != Nav4.Kind.JUMP);
        mc.options.keyJump.setDown(jump);

        // stuck: not 1.5 blocks further in 4 s
        if (stuckFrom == null || p.position().distanceTo(stuckFrom) > 1.5) {
            stuckFrom = p.position();
            stuckAt = now;
        } else if (now - stuckAt > 4000) {
            stuckAt = now;
            return block(t.x(), t.y(), t.z(), "stuck on the way to the next cell");
        }
        return Status.WALKING;
    }
}
