package com.nablzz.addons.mudworm;

import com.nablzz.addons.mudworm.Config;
import com.nablzz.addons.mudworm.path.Cells;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class Etherwarp {
    public static final double SNEAK_EYE = 1.27;

    private Etherwarp() {
    }

    public static boolean hasEther(ItemStack st) {
        CustomData cd = (CustomData)st.get(DataComponents.CUSTOM_DATA);
        return cd != null && cd.copyTag().getIntOr("ethermerge", 0) == 1;
    }

    public static double range(ItemStack st) {
        int tuners = 0;
        CustomData cd = (CustomData)st.get(DataComponents.CUSTOM_DATA);
        if (cd != null) {
            tuners = Math.max(0, Math.min(4, cd.copyTag().getIntOr("tuned_transmission", 0)));
        }
        return (double)(Config.baseRange + tuners) - Config.rangeMargin;
    }

    public static boolean interactable(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof AbstractCauldronBlock || b instanceof NoteBlock || b instanceof DiodeBlock;
    }

    public static boolean validTarget(Cells c, int x, int y, int z) {
        return (c.at(x, y, z) & 8) != 0 && (c.at(x, y + 1, z) & 2) != 0 && (c.at(x, y + 2, z) & 2) != 0;
    }

    public static BlockPos firstStop(Cells c, Vec3 from, Vec3 dir, double maxDist, boolean airOnly) {
        return Etherwarp.walk(c, from, dir, maxDist, airOnly ? 1 : 0);
    }

    public static BlockPos firstSolid(Cells c, Vec3 from, Vec3 dir, double maxDist) {
        return Etherwarp.walk(c, from, dir, maxDist, 2);
    }

    private static BlockPos walk(Cells c, Vec3 from, Vec3 dir, double maxDist, int mode) {
        boolean bl;
        boolean passes;
        boolean strict;
        double ty;
        double tx;
        double dz;
        int sy;
        int sx;
        int x = (int)Math.floor(from.x);
        int y = (int)Math.floor(from.y);
        int z = (int)Math.floor(from.z);
        int n = dir.x > 0.0 ? 1 : (sx = dir.x < 0.0 ? -1 : 0);
        int n2 = dir.y > 0.0 ? 1 : (sy = dir.y < 0.0 ? -1 : 0);
        int sz = dir.z > 0.0 ? 1 : (dir.z < 0.0 ? -1 : 0);
        double dx = sx != 0 ? Math.abs(1.0 / dir.x) : Double.POSITIVE_INFINITY;
        double dy = sy != 0 ? Math.abs(1.0 / dir.y) : Double.POSITIVE_INFINITY;
        double d = dz = sz != 0 ? Math.abs(1.0 / dir.z) : Double.POSITIVE_INFINITY;
        double d2 = sx > 0 ? ((double)(x + 1) - from.x) * dx : (tx = sx < 0 ? (from.x - (double)x) * dx : Double.POSITIVE_INFINITY);
        double d3 = sy > 0 ? ((double)(y + 1) - from.y) * dy : (ty = sy < 0 ? (from.y - (double)y) * dy : Double.POSITIVE_INFINITY);
        double tz = sz > 0 ? ((double)(z + 1) - from.z) * dz : (sz < 0 ? (from.z - (double)z) * dz : Double.POSITIVE_INFINITY);
        boolean bl2 = strict = mode == 1 || mode == 0 && Config.strictRay;
        do {
            double t;
            if (tx < ty && tx < tz) {
                x += sx;
                t = tx;
                tx += dx;
            } else if (ty < tz) {
                y += sy;
                t = ty;
                ty += dy;
            } else {
                z += sz;
                t = tz;
                tz += dz;
            }
            if (t > maxDist) {
                return null;
            }
            short f = c.at(x, y, z);
            if ((f & 1) == 0) {
                return null;
            }
            if (mode == 2) {
                if ((f & 0x90) == 0) {
                    bl = true;
                    continue;
                }
                bl = false;
                continue;
            }
            bl = (f & 2) != 0 || !strict && (f & 4) != 0;
        } while (passes = bl);
        return new BlockPos(x, y, z);
    }

    public static BlockPos predict(Cells c, Vec3 eye, float yaw, float pitch, double range) {
        BlockPos hit = Etherwarp.firstStop(c, eye, Etherwarp.dir(yaw, pitch), range, false);
        return hit != null && Etherwarp.validTarget(c, hit.getX(), hit.getY(), hit.getZ()) ? hit : null;
    }

    public static boolean coneSafe(Cells c, Vec3 eye, float yaw, float pitch, double range) {
        float[][] offs;
        float j = (float)Config.coneDeg;
        for (float[] o : offs = new float[][]{{j, 0.0f}, {-j, 0.0f}, {0.0f, j}, {0.0f, -j}}) {
            BlockPos hit = Etherwarp.firstStop(c, eye, Etherwarp.dir(yaw + o[0], pitch + o[1]), range, false);
            if (hit == null || Etherwarp.validTarget(c, hit.getX(), hit.getY(), hit.getZ())) continue;
            return false;
        }
        return true;
    }

    public static boolean robust(Cells c, Vec3 eye, float yaw, float pitch, BlockPos target, double range) {
        if (!target.equals((Object)Etherwarp.firstStop(c, eye, Etherwarp.dir(yaw, pitch), range, false))) {
            return false;
        }
        return Etherwarp.coneSafe(c, eye, yaw, pitch, range);
    }

    public static Shot shot(Cells c, Vec3 eye, BlockPos target, double range) {
        Vec3[] aims;
        if (eye.distanceTo(target.getCenter()) > range || !Etherwarp.validTarget(c, target.getX(), target.getY(), target.getZ())) {
            return null;
        }
        for (Vec3 a : aims = new Vec3[]{new Vec3((double)target.getX() + 0.5, (double)target.getY() + 0.97, (double)target.getZ() + 0.5), target.getCenter()}) {
            float[] ang = Etherwarp.angles(eye, a);
            if (!Etherwarp.robust(c, eye, ang[0], ang[1], target, range)) continue;
            return new Shot(target, ang[0], ang[1]);
        }
        return null;
    }

    public static int reject(Cells c, Vec3 eye, BlockPos target, double range) {
        if (eye.distanceTo(target.getCenter()) > range) {
            return 1;
        }
        if (!Etherwarp.validTarget(c, target.getX(), target.getY(), target.getZ())) {
            return 2;
        }
        return 3;
    }

    public static Vec3 dir(float yaw, float pitch) {
        double yr = Math.toRadians(-yaw) - Math.PI;
        double pr = Math.toRadians(-pitch);
        double cp = -Math.cos(pr);
        return new Vec3(Math.sin(yr) * cp, Math.sin(pr), Math.cos(yr) * cp);
    }

    public static float[] angles(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        float yaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        return new float[]{yaw, pitch};
    }

    public static Vec3 eyeAt(BlockPos feet) {
        return new Vec3((double)feet.getX() + 0.5, (double)feet.getY() + 1.27, (double)feet.getZ() + 0.5);
    }

    public record Shot(BlockPos target, float yaw, float pitch) {
        public BlockPos landing() {
            return this.target.above();
        }
    }
}

