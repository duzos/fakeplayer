package dev.duzo.players.entities.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;

/** What a block state costs to place. Wall variants already map to the standing item through the vanilla block-to-item map. */
public final class MaterialRules {
	public record Cost(Item item, int count, boolean supported) {
		static final Cost NONE = new Cost(Items.AIR, 0, true);
		static final Cost UNSUPPORTED = new Cost(Items.AIR, 0, false);
	}

	private static final IntegerProperty[] STACKED = {
			BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.EGGS, BlockStateProperties.LAYERS,
			BlockStateProperties.FLOWER_AMOUNT
	};

	private MaterialRules() {}

	public static Cost cost(BlockState state) {
		if (state.isAir()) return Cost.NONE;
		if (state.getBlock() instanceof LiquidBlock) return Cost.UNSUPPORTED;
		// the second half of a door, tall plant or bed comes with the first
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return Cost.NONE;
		if (state.hasProperty(BlockStateProperties.BED_PART)
				&& state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return Cost.NONE;
		Item item = state.getBlock().asItem();
		if (item == Items.AIR) return Cost.UNSUPPORTED;
		if (state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0.0F) return Cost.UNSUPPORTED;
		int count = 1;
		if (state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
			count = 2;
		}
		for (IntegerProperty p : STACKED) {
			if (state.hasProperty(p)) count = state.getValue(p);
		}
		return new Cost(item, count, true);
	}
}
