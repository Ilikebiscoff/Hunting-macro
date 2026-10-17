package com.nablzz.addons.mudworm;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Reads the blocks of the client level for {@link Nav4}. */
final class Nav4World implements Nav4.World {
    private final ClientLevel level;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

    Nav4World(ClientLevel level) {
        this.level = level;
    }

    @Override
    public int flags(int x, int y, int z) {
        m.set(x, y, z);
        if (level.isOutsideBuildHeight(m)) return y < 64 ? Nav4.UNKNOWN : 0; // under the world: void; above it: open sky
        if (!level.isLoaded(m)) return Nav4.UNKNOWN;
        BlockState st = level.getBlockState(m);
        var b = st.getBlock();
        int f = 0;
        var fluid = st.getFluidState();
        if (fluid.is(FluidTags.LAVA)) f |= Nav4.LAVA;
        else if (!fluid.isEmpty()) f |= Nav4.WATER;
        if (b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.WITHER_ROSE || b == Blocks.CAMPFIRE
                || b == Blocks.SOUL_CAMPFIRE || b == Blocks.POWDER_SNOW || b == Blocks.COBWEB) f |= Nav4.HAZARD;
        var shape = st.getCollisionShape(level, m);
        if (!shape.isEmpty()) {
            double top = shape.max(Direction.Axis.Y);
            if (top <= 0.6) f |= Nav4.LOW;
            else if (top > 1.0) f |= Nav4.SOLID | Nav4.TALLX;
            else f |= Nav4.SOLID;
        }
        return f;
    }
}
