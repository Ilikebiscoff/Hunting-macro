package com.nablzz.addons.minerzombie;

import com.nablzz.addons.mudworm.Etherwarp;
import com.nablzz.addons.mudworm.path.Cell;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Etherwarp for the hunt: finds a block next to a far zombie that a warp can reach and works out where to aim. The sneak,
 * aim and click are done by the ETHER phase of {@link HuntController}. The ray / landing rules are the ones of the Galatea
 * mudworm macro ({@link Etherwarp}).
 */
final class EtherHop {
    /** Landing spots are searched this far (blocks) around the zombie; never closer than {@link #MIN_STAND}. */
    private static final int AROUND = 8;
    private static final double MIN_STAND = 3.0;
    /** How many of the nearest-to-the-zombie spots get the (more expensive) ray check before giving up. */
    private static final int TRIES = 80;

    private EtherHop() {}

    /** Hotbar slot of an Aspect of the Void / End that has Ether Transmission, or -1. */
    static int slot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty() || !Etherwarp.hasEther(st)) continue;
            String name = st.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (name.contains("aspect of the void") || name.contains("aspect of the end")) return i;
        }
        return -1;
    }

    private record Spot(BlockPos target, double toZombie) {}

    /**
     * A warp that ends within {@link #AROUND} blocks of {@code zombie}, or null when no spot is reachable from here.
     *
     * @param avoid   landing positions (feet, {@link BlockPos#asLong}) that failed before
     * @param allowed extra rule for the landing position (feet), e.g. "inside the scan box"
     */
    static Etherwarp.Shot plan(Level level, LocalPlayer p, int slot, Vec3 zombie, Set<Long> avoid, Predicate<BlockPos> allowed) {
        Cell.Live live = new Cell.Live(level);
        Vec3 eye = new Vec3(p.getX(), p.getY() + Etherwarp.SNEAK_EYE, p.getZ());
        double range = Etherwarp.range(p.getInventory().getItem(slot));
        int zx = (int) Math.floor(zombie.x), zy = (int) Math.floor(zombie.y) - 1, zz = (int) Math.floor(zombie.z);

        List<Spot> spots = new ArrayList<>();
        for (int dx = -AROUND; dx <= AROUND; dx++) {
            for (int dz = -AROUND; dz <= AROUND; dz++) {
                for (int y = zy + 3; y >= zy - 6; y--) {
                    int x = zx + dx, z = zz + dz;
                    Vec3 stand = new Vec3(x + 0.5, y + 1, z + 0.5);
                    double d = stand.distanceTo(zombie);
                    if (d < MIN_STAND || d > AROUND) continue;
                    if (!Etherwarp.validTarget(live, x, y, z)) continue;
                    BlockPos target = new BlockPos(x, y, z);
                    if (eye.distanceTo(target.getCenter()) > range) continue;
                    if (avoid.contains(target.above().asLong()) || !allowed.test(target.above())) continue;
                    if (hazardNear(live, x, y, z)) continue;
                    spots.add(new Spot(target, d));
                }
            }
        }
        spots.sort((a, b) -> Double.compare(a.toZombie, b.toZombie));
        for (int i = 0; i < Math.min(TRIES, spots.size()); i++) {
            Etherwarp.Shot s = Etherwarp.shot(live, eye, spots.get(i).target, range);
            if (s != null) return s;
        }
        return null;
    }

    /** Lava, fire and the like on or right next to the landing block (feet and the block below). */
    private static boolean hazardNear(Cell.Live live, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if ((live.at(x + dx, y + dy, z + dz) & Cell.HAZARD) != 0) return true;
                }
            }
        }
        return false;
    }
}
