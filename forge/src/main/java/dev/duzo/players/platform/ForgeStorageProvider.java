package dev.duzo.players.platform;

import dev.duzo.players.entities.ai.storage.ExternalStorage;
import dev.duzo.players.platform.services.IStorageProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;

public class ForgeStorageProvider implements IStorageProvider {

	// The unsided view first, then faces in a fixed order. Only one view is ever used, because two
	// sides of one block can be distinct objects over the same inventory.
	private static final Direction[] SIDES = {null, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

	@Override
	@Nullable
	public ExternalStorage find(Level level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null) return null;
		for (Direction side : SIDES) {
			IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).resolve().orElse(null);
			if (handler != null && handler.getSlots() > 0) return new HandlerStorage(handler);
		}
		return null;
	}

	private static final class HandlerStorage extends ExternalStorage {
		private final IItemHandler handler;

		HandlerStorage(IItemHandler handler) {
			this.handler = handler;
		}

		@Override
		public Object identity() {
			return handler;
		}

		@Override
		public int getContainerSize() {
			return handler.getSlots();
		}

		@Override
		protected ItemStack peek(int slot) {
			return handler.getStackInSlot(slot).copy();
		}

		@Override
		public int slotLimit(int slot, ItemStack stack) {
			return handler.getSlotLimit(slot);
		}

		@Override
		public ItemStack insert(ItemStack stack, boolean simulate) {
			if (stack.isEmpty()) return ItemStack.EMPTY;
			ItemStack remaining = stack.copy();
			for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
				remaining = handler.insertItem(i, remaining, simulate);
			}
			return remaining;
		}

		@Override
		public ItemStack extract(int slot, int amount, boolean simulate) {
			if (amount <= 0) return ItemStack.EMPTY;
			ItemStack inSlot = handler.getStackInSlot(slot);
			if (inSlot.isEmpty()) return ItemStack.EMPTY;
			return handler.extractItem(slot, Math.min(amount, inSlot.getMaxStackSize()), simulate);
		}
	}
}
