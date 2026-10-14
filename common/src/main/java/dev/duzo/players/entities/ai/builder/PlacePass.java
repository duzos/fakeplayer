package dev.duzo.players.entities.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/** When in a build a cell is worked. Placement order is CLEAR top-down, then SOLID and ATTACH bottom-up. */
enum PlacePass {
	CLEAR,
	/** Full collision shape, so it can carry anything attached to it. */
	SOLID,
	/** Everything else: torches, rails, stairs, slabs, doors. Needs its neighbours to exist first. */
	ATTACH,
	/** Skipped in v1. */
	FLUID;

	private static final Property<?>[] IDENTITY = {
			BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.AXIS,
			BlockStateProperties.HALF, BlockStateProperties.SLAB_TYPE, BlockStateProperties.DOUBLE_BLOCK_HALF,
			BlockStateProperties.BED_PART
	};

	static PlacePass of(BlockState target) {
		if (target.isAir()) return CLEAR;
		if (target.getBlock() instanceof LiquidBlock) return FLUID;
		return target.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? SOLID : ATTACH;
	}

	/** Whether the world already holds `want`. Only the identity properties are compared: redstone power,
	 * stair shape and waterlogging follow from the surroundings and would never settle. */
	static boolean matches(BlockState want, BlockState have) {
		if (want.isAir()) return have.isAir() || have.getBlock() instanceof LiquidBlock;
		if (want.getBlock() != have.getBlock()) return false;
		for (Property<?> p : IDENTITY) {
			if (want.hasProperty(p) && !want.getValue(p).equals(have.getValue(p))) return false;
		}
		return true;
	}
}
