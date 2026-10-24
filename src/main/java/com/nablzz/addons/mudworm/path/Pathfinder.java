package com.nablzz.addons.mudworm.path;

import com.nablzz.addons.mudworm.Config;
import com.nablzz.addons.mudworm.Etherwarp;
import com.nablzz.addons.mudworm.path.WorldCache;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

public final class Pathfinder {
    private static final int MAX_HOPS = 14;
    private static final long TIMEOUT_NS = 2000000000L;
    private static final double MIN_PROGRESS = 1.5;
    private static final int MAX_SIDEWAYS = 1;
    private static final double[] YAW_OFFSETS = new double[]{0.0, 2.0, -2.0, 4.0, -4.0, 7.0, -7.0, 11.0, -11.0, 16.0, -16.0, 24.0, -24.0, 36.0, -36.0, 55.0, -55.0, 80.0, -80.0, 120.0, -120.0, 180.0};
    private static final int KEEP = 8;
    private final WorldCache.Reader c;
    private final Goal goal;
    private final double range;
    private final BlockPos startFeet;
    private final Vec3 startEye;
    private final Set<Long> avoid;
    private final Limits limits;
    private final float refYaw;
    private final float refPitch;
    private final float step;
    private volatile State state = State.RUNNING;
    private volatile boolean cancelled;
    private volatile List<Step> result = List.of();
    private int rays;
    private int hits;
    private int hopsFound;
    private long elapsedMs;
    private volatile String outcome = "running";
    private String endNote = "";
    private boolean needLos = true;
    private boolean lastReached;

    public Pathfinder(WorldCache cache, BlockPos startFeet, Vec3 startEye, Goal goal, double range, Set<Long> avoid, Limits limits, float refYaw, float refPitch, float step) {
        this.c = cache.reader();
        this.goal = goal;
        this.range = range;
        this.startFeet = startFeet;
        this.startEye = startEye;
        this.avoid = avoid;
        this.limits = limits;
        this.refYaw = refYaw;
        this.refPitch = refPitch;
        this.step = step;
    }

    public State state() {
        return this.state;
    }

    public int expansions() {
        return this.hopsFound;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public List<Step> result() {
        return this.result;
    }

    public String report() {
        return String.format("%s%s | %d ms | %d hops planned, %d rays traced, %d ended on a valid landing", this.outcome, this.endNote, this.elapsedMs, this.hopsFound, this.rays, this.hits);
    }

    public void run() {
        long t0 = System.nanoTime();
        try {
            List<Step> steps = this.route(true, t0 + 1000000000L);
            boolean reached = this.lastReached;
            String note = this.endNote;
            if (!reached && this.goal.spot() != null && !this.cancelled) {
                List<Step> relaxed = this.route(false, t0 + 2000000000L);
                if (this.lastReached) {
                    steps = relaxed;
                    reached = true;
                    note = " (no landing with a clear view of the spot: through a wall)";
                } else if (steps.isEmpty()) {
                    steps = relaxed;
                    note = this.endNote;
                }
            }
            this.endNote = note;
            this.result = steps;
            this.elapsedMs = (System.nanoTime() - t0) / 1000000L;
            if (this.cancelled) {
                this.outcome = "cancelled";
                this.state = State.FAILED;
            } else if (steps.isEmpty() && !reached) {
                this.outcome = "FAILED: no hop gets closer to the goal";
                this.state = State.FAILED;
            } else {
                this.outcome = (reached ? "FOUND " : "PARTIAL ") + steps.size() + " hops";
                this.state = State.FOUND;
            }
            return;
        }
        catch (Throwable t) {
            this.outcome = "ERROR " + String.valueOf(t);
            t.printStackTrace();
            this.elapsedMs = (System.nanoTime() - t0) / 1000000L;
            this.state = State.FAILED;
            return;
        }
    }

    private List<Step> route(boolean los, long deadline) {
        this.needLos = los;
        this.endNote = "";
        ArrayList<Step> steps = new ArrayList<Step>();
        BlockPos feet = this.startFeet;
        Vec3 eye = this.startEye;
        double here = this.score(feet);
        int sideways = 0;
        boolean reached = this.isGoal(feet);
        while (!reached && steps.size() < 14 && !this.cancelled && System.nanoTime() < deadline) {
            List<Cand> best = this.bestHops(eye, feet, steps);
            Cand pick = null;
            boolean side = false;
            for (Cand cd : best) {
                if (!this.awayFromRoute(cd.target().above(), steps, 9) || !(cd.score() <= here - 1.5)) continue;
                pick = cd;
                break;
            }
            if (pick == null && this.goal.spot() != null && sideways < 1) {
                for (Cand cd : best) {
                    if (!this.awayFromRoute(cd.target().above(), steps, 64) || !(cd.score() < here + 8.0)) continue;
                    pick = cd;
                    side = true;
                    break;
                }
            }
            if (pick == null) {
                this.endNote = " (no hop gets closer, " + best.size() + " candidates)";
                break;
            }
            if (side) {
                ++sideways;
            }
            BlockPos land = pick.target().above();
            steps.add(new Step(land, new Etherwarp.Shot(pick.target(), pick.yaw(), pick.pitch())));
            this.hopsFound = steps.size();
            feet = land;
            eye = Etherwarp.eyeAt(land);
            here = this.score(land);
            reached = this.isGoal(land);
        }
        if (!reached && !steps.isEmpty() && here > this.score(this.startFeet) - 1.5) {
            steps.clear();
            this.endNote = " (route made no progress)";
        }
        this.lastReached = reached;
        return steps;
    }

    private boolean isGoal(BlockPos feet) {
        if (this.goal.spot() == null) {
            double hd = Math.hypot((double)feet.getX() + 0.5 - this.goal.center().x, (double)feet.getZ() + 0.5 - this.goal.center().z);
            return hd <= this.goal.radius() && Math.abs((double)feet.getY() - this.goal.center().y) <= 8.0;
        }
        return this.canClick(feet.getX(), feet.getY(), feet.getZ(), this.goal.spot());
    }

    private boolean canClick(int x, int y, int z, BlockPos spot) {
        Vec3[] aims;
        Vec3 eye = new Vec3((double)x + 0.5, (double)y + 1.5, (double)z + 0.5);
        double r = Config.reach - 0.3;
        if (eye.distanceToSqr(spot.getCenter()) > r * r) {
            return false;
        }
        if (!this.needLos) {
            return true;
        }
        for (Vec3 a : aims = new Vec3[]{new Vec3((double)spot.getX() + 0.5, (double)spot.getY() + 0.95, (double)spot.getZ() + 0.5), spot.getCenter()}) {
            BlockPos hit = Etherwarp.firstSolid(this.c, eye, a.subtract(eye).normalize(), eye.distanceTo(a) + 0.5);
            if (!spot.equals((Object)hit)) continue;
            return true;
        }
        return false;
    }

    private double score(BlockPos feet) {
        if (this.isGoal(feet)) {
            return -1.0;
        }
        double dx = (double)feet.getX() + 0.5 - this.goal.center().x;
        double dy = (double)feet.getY() + 1.62 - this.goal.center().y;
        double dz = (double)feet.getZ() + 0.5 - this.goal.center().z;
        return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - this.goal.radius());
    }

    private boolean awayFromRoute(BlockPos land, List<Step> steps, int minDistSqr) {
        if (land.distSqr((Vec3i)this.startFeet) < (double)minDistSqr) {
            return false;
        }
        for (Step s : steps) {
            if (!(land.distSqr((Vec3i)s.feet()) < (double)minDistSqr)) continue;
            return false;
        }
        return true;
    }

    private List<Cand> bestHops(Vec3 eye, BlockPos feet, List<Step> route) {
        float baseYaw = Etherwarp.angles(eye, this.goal.center())[0];
        ArrayList<Cand> found = new ArrayList<Cand>();
        for (double yo : YAW_OFFSETS) {
            if (this.cancelled) break;
            float yaw = (float)((double)baseYaw + yo);
            for (float pitch = -85.0f; pitch <= 89.0f; pitch += 1.0f) {
                Cand cd = this.trace(eye, yaw, pitch);
                if (cd == null) continue;
                found.add(cd);
            }
        }
        found.sort((a, b) -> Double.compare(a.score(), b.score()));
        ArrayList<Cand> top = new ArrayList<Cand>();
        HashSet<BlockPos> seen = new HashSet<BlockPos>();
        int tested = 0;
        for (Cand cd : found) {
            if (!seen.add(cd.target())) continue;
            if (++tested > 700) break;
            if (!this.robust(eye, cd)) continue;
            top.add(cd);
            if (top.size() < 8) continue;
            break;
        }
        ArrayList<Cand> out = new ArrayList<Cand>();
        for (Cand cd : top) {
            Cand r = this.refine(eye, cd);
            out.add(r != cd && this.robust(eye, r) ? r : cd);
        }
        out.sort((a, b) -> Double.compare(a.score(), b.score()));
        return out;
    }

    private Cand trace(Vec3 eye, float yaw, float pitch) {
        if (this.step > 0.0f) {
            yaw = this.refYaw + (float)Math.round((yaw - this.refYaw) / this.step) * this.step;
            pitch = this.refPitch + (float)Math.round((pitch - this.refPitch) / this.step) * this.step;
        }
        pitch = Math.max(-90.0f, Math.min(90.0f, pitch));
        ++this.rays;
        BlockPos hit = Etherwarp.firstStop(this.c, eye, Etherwarp.dir(yaw, pitch), this.range, false);
        if (hit == null || !Etherwarp.validTarget(this.c, hit.getX(), hit.getY(), hit.getZ())) {
            return null;
        }
        BlockPos land = hit.above();
        if (!this.limits.contains(land)) {
            return null;
        }
        if (!this.avoid.isEmpty() && this.avoid.contains(land.asLong())) {
            return null;
        }
        ++this.hits;
        return new Cand(this.score(land), yaw, pitch, hit);
    }

    private Cand refine(Vec3 eye, Cand start) {
        float[][] passes;
        Cand best = start;
        float g = this.step > 0.0f ? this.step : 0.05f;
        for (float[] pass : passes = new float[][]{{4.0f * g, 6.0f}, {g, 4.0f}}) {
            float st = pass[0];
            int n = (int)pass[1];
            Cand center = best;
            for (int iy = -n; iy <= n; ++iy) {
                for (int ip = -n; ip <= n; ++ip) {
                    Cand cd;
                    if (iy == 0 && ip == 0 || (cd = this.trace(eye, center.yaw() + (float)iy * st, center.pitch() + (float)ip * st)) == null || !(cd.score() < best.score())) continue;
                    best = cd;
                }
            }
        }
        return best;
    }

    private boolean robust(Vec3 eye, Cand cd) {
        return Etherwarp.coneSafe(this.c, eye, cd.yaw(), cd.pitch(), this.range);
    }

    public static enum State {
        RUNNING,
        FOUND,
        FAILED;

    }

    public record Goal(BlockPos spot, Vec3 center, double radius) {
        public static Goal spot(BlockPos s) {
            return new Goal(s, s.getCenter(), Config.reach);
        }

        public static Goal area(Vec3 c, double r) {
            return new Goal(null, c, r);
        }
    }

    public record Limits(int minY, int maxY, int minX, int maxX, int minZ, int maxZ) {
        public boolean contains(BlockPos p) {
            return p.getY() >= this.minY && p.getY() <= this.maxY && p.getX() >= this.minX && p.getX() <= this.maxX && p.getZ() >= this.minZ && p.getZ() <= this.maxZ;
        }

        @Override
        public String toString() {
            return "y " + this.minY + ".." + this.maxY + ", x " + this.minX + ".." + this.maxX + ", z " + this.minZ + ".." + this.maxZ;
        }
    }

    private record Cand(double score, float yaw, float pitch, BlockPos target) {
    }

    public record Step(BlockPos feet, Etherwarp.Shot shot) {
    }
}

