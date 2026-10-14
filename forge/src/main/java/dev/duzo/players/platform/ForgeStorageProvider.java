package dev.duzo.players.platform;

import dev.duzo.players.entities.ai.storage.ExternalStorage;
import dev.duzo.players.platform.services.IStorageProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import javax.annotation.Nullable;

public class ForgeStorageProvider implements IStorageProvider {

	// The unsided view first, then faces in a fixed order. Only one view is ever used, because two
	// sides of one block can be distinct objects over the same inventory.
	private static final Direction[] SIDES = {null, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

	@Override
	@Nullable
	public ExternalStorage find(Level level, BlockPos pos) {
		for (Direction side : SIDES) {
			ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, side);
			if (handler != null && handler.size() > 0) return new HandlerStorage(handler);
		}
		return null;
	}

	// Nest inside a caller's open transaction rather than throwing, since the request API is public
	// and a resolver may already hold one.
	@SuppressWarnings("deprecation")
	private static Transaction open() {
		return Transaction.open(Transaction.getCurrentOpenedTransaction());
	}

	private static final class HandlerStorage extends ExternalStorage {
		private final ResourceHandler<ItemResource> handler;

		HandlerStorage(ResourceHandler<ItemResource> handler) {
			this.handler = handler;
		}

		@Override
		public Object identity() {
			return handler;
		}

		@Override
		public int getContainerSize() {
			return handler.size();
		}

		@Override
		protected ItemStack peek(int slot) {
			ItemResource resource = handler.getResource(slot);
			return resource.isEmpty() ? ItemStack.EMPTY : resource.toStack(handler.getAmountAsInt(slot));
		}

		@Override
		public int slotLimit(int slot, ItemStack stack) {
			return handler.getCapacityAsInt(slot, ItemResource.of(stack));
		}

		@Override
		public ItemStack insert(ItemStack stack, boolean simulate) {
			if (stack.isEmpty()) return ItemStack.EMPTY;
			int inserted;
			try (Transaction tx = open()) {
				inserted = handler.insert(ItemResource.of(stack), stack.getCount(), tx);
				if (!simulate) tx.commit();
			}
			return stack.copyWithCount(stack.getCount() - inserted);
		}

		@Override
		public ItemStack extract(int slot, int amount, boolean simulate) {
			if (amount <= 0) return ItemStack.EMPTY;
			ItemResource resource = handler.getResource(slot);
			if (resource.isEmpty()) return ItemStack.EMPTY;
			int extracted;
			try (Transaction tx = open()) {
				extracted = handler.extract(slot, resource, Math.min(amount, resource.getMaxStackSize()), tx);
				if (!simulate) tx.commit();
			}
			return extracted <= 0 ? ItemStack.EMPTY : resource.toStack(extracted);
		}
	}
}
