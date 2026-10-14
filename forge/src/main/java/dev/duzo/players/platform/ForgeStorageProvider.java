package dev.duzo.players.platform;

import dev.duzo.players.Constants;
import dev.duzo.players.Constants;
import dev.duzo.players.entities.ai.storage.ExternalStorage;
import dev.duzo.players.platform.services.IStorageProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;

public class ForgeStorageProvider implements IStorageProvider {

	// The unsided view first, then faces in a fixed order. Only one view is ever used, because two
	// sides of one block can be distinct objects over the same inventory.
	private static final Direction[] SIDES = {null, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

	@Override
	@Nullable
	public ExternalStorage find(Level level, BlockPos pos) {
		for (Direction side : SIDES) {
			IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
			if (handler != null && handler.getSlots() > 0) {
				Constants.trace("storage/" + pos, "neoforge storage at {} side {} = {} size={}", pos, side,
						handler.getClass().getName(), handler.getSlots());
				return new HandlerStorage(handler);
			}
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
			// getStackInSlot must never be mutated, so every read is a copy.
			return handler.getStackInSlot(slot).copy();
		}

		@Override
		public int slotLimit(int slot, ItemStack stack) {
			int limit = handler.getSlotLimit(slot);
			// A plain handler reports 64 for every item and leaves the item's own cap to the caller,
			// while a drawer reports a real capacity far above it.
			return limit <= 64 ? Math.min(limit, stack.getMaxStackSize()) : limit;
		}

		@Override
		public ItemStack insert(ItemStack stack, boolean simulate) {
			if (stack.isEmpty()) return ItemStack.EMPTY;
			ItemStack remaining = stack.copy();
			for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
				remaining = handler.insertItem(slot, remaining, simulate);
			}
			return remaining;
		}

		@Override
		public ItemStack extract(int slot, int amount, boolean simulate) {
			if (amount <= 0) return ItemStack.EMPTY;
			ItemStack present = handler.getStackInSlot(slot);
			if (present.isEmpty()) return ItemStack.EMPTY;
			ItemStack out = handler.extractItem(slot, Math.min(amount, present.getMaxStackSize()), simulate);
			Constants.traceEvent("neoforge extract slot {} {} x{} sim={} -> {}",
					slot, present.getItem(), amount, simulate, out.getCount());
			return out;
		}
	}
}
