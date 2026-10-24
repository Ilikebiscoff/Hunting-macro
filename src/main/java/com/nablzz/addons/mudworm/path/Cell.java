package com.nablzz.addons.mudworm.path;

import com.nablzz.addons.mudworm.Etherwarp;
import com.nablzz.addons.mudworm.path.Cells;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class Cell {
    public static final int LOADED = 1;
    public static final int AIR = 2;
    public static final int RAYPASS = 4;
    public static final int TARGET = 8;
    public static final int COLL = 16;
    public static final int LOW = 32;
    public static final int TALL = 64;
    public static final int FLUID = 128;
    public static final int HAZARD = 256;
    public static final int SLOW = 512;
    public static final int BREAKABLE = 1024;
    public static final short SKY = 7;

    private Cell() {
    }

    public static short classify(Level level, BlockPos pos, BlockState s) {
        Block b;
        VoxelShape shape;
        boolean coll;
        boolean wet;
        if (s.isAir()) {
            return 7;
        }
        int f = 1;
        FluidState fluid = s.getFluidState();
        boolean bl = wet = !fluid.isEmpty();
        if (wet) {
            f |= 0x80;
            if (fluid.is(FluidTags.LAVA)) {
                f |= 0x100;
            }
        }
        boolean bl2 = coll = !(shape = s.getCollisionShape((BlockGetter)level, pos)).isEmpty();
        if (coll) {
            f |= 0x10;
            double top = shape.max(Direction.Axis.Y);
            if (top <= 0.6) {
                f |= 0x20;
            } else if (top > 1.0) {
                f |= 0x40;
            }
        }
        if ((b = s.getBlock()) == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.WITHER_ROSE || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE || b == Blocks.POWDER_SNOW || b == Blocks.COBWEB) {
            f |= 0x100;
        }
        if (b == Blocks.AZALEA_LEAVES || b == Blocks.FLOWERING_AZALEA_LEAVES) {
            f |= 0x400;
        }
        if (b == Blocks.SOUL_SAND || b == Blocks.HONEY_BLOCK || b == Blocks.MUD) {
            f |= 0x200;
        }
        if (!(coll || wet || s.hasBlockEntity() || b instanceof BasePressurePlateBlock || b instanceof TripWireBlock || b instanceof ButtonBlock)) {
            f |= 4;
        }
        if (coll && !wet && (f & 0x100) == 0 && !Etherwarp.interactable(s) && s.isCollisionShapeFullBlock((BlockGetter)level, pos)) {
            f |= 8;
        }
        return (short)f;
    }

    public static final class Live
    implements Cells {
        private final Level level;
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        public Live(Level level) {
            this.level = level;
        }

        @Override
        public short at(int x, int y, int z) {
            this.m.set(x, y, z);
            if (this.level.isOutsideBuildHeight((BlockPos)this.m) || !this.level.isLoaded((BlockPos)this.m)) {
                return 0;
            }
            return Cell.classify(this.level, (BlockPos)this.m, this.level.getBlockState((BlockPos)this.m));
        }
    }
}

