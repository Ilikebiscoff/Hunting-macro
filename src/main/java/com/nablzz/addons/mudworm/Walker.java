package com.nablzz.addons.mudworm;

import com.nablzz.addons.minerzombie.HumanAim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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
    private long jumpedAt;

    boolean hasPath() {
        return path.size() >= 2;
    }

    void clear() {
        path = List.of();
        idx = 1;
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
            double d = Math.hypot(n.x() + 0.5 - px, n.z() + 0.5 - pz);
            boolean level = Math.abs(n.y() - p.getY()) < 1.3;
            boolean last = idx == path.size() - 1;
            if (d < (last ? 0.4 : 0.5) && level) {
                idx++;
                continue;
            }
            if (!last && p.onGround() && d < 1.2) { // cut the corner when the next node is already closer
                Nav4.Node nx = path.get(idx + 1);
                double d2 = Math.hypot(nx.x() + 0.5 - px, nx.z() + 0.5 - pz);
                boolean plain = nx.kind() == Nav4.Kind.WALK || nx.kind() == Nav4.Kind.FALL;
                if (plain && n.kind() != Nav4.Kind.JUMP && n.kind() != Nav4.Kind.GAP && d2 < d
                        && Math.abs(nx.y() - p.getY()) < 1.3) {
                    idx++;
                    continue;
                }
            }
            break;
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
                // walking (or stepping down): look at the cells right ahead, never walk into a wall
                if (dist > 0.35 && err < 40f) {
                    double hx = dx / dist, hz = dz / dist;
                    int ax = Mth.floor(px + hx * 0.8), az = Mth.floor(pz + hz * 0.8);
                    int cy = Mth.floor(p.getY() + 0.05);
                    boolean intoTarget = ax == t.x() && az == t.z();
                    if (wall(w, ax, cy, az) && !(intoTarget && cy < t.y())) {
                        // a solid block ahead that the path does not climb: is it a block we can simply hop onto?
                        boolean hop = (w.flags(ax, cy, az) & Nav4.TALLX) == 0 && passable(w, ax, cy + 1, az) && passable(w, ax, cy + 2, az)
                                && passable(w, Mth.floor(px), cy + 2, Mth.floor(pz));
                        if (hop && (w.flags(ax, cy + 1, az) & Nav4.SOLID) == 0 && ground(w, ax, cy, az)) {
                            jump = ground;
                        } else {
                            return block(ax, cy, az, "wall ahead");
                        }
                    } else if (ground && p.horizontalCollision && now - jumpedAt > 700
                            && passable(w, Mth.floor(px), cy + 2, Mth.floor(pz))) {
                        jump = true; // bumped into something low that the world data didn't show: hop (only with room above)
                    }
                }
            }
        }
        if (jump) jumpedAt = now;

        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(sprint && forward && err < 20f && t.kind() != Nav4.Kind.JUMP);
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
