package dev.duzo.players.platform;

import dev.duzo.players.entities.ai.storage.ExternalStorage;
import dev.duzo.players.platform.services.IStorageProvider;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class FabricStorageProvider implements IStorageProvider {

	// The unsided view first, then faces in a fixed order. Only one view is ever used, because two
	// sides of one block can be distinct objects over the same inventory.
	private static final Direction[] SIDES = {null, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

	@Override
	@Nullable
	public ExternalStorage find(Level level, BlockPos pos) {
		for (Direction side : SIDES) {
			Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos, side);
			if (storage != null && (storage.supportsInsertion() || storage.supportsExtraction())) {
				return new FabricStorage(storage);
			}
		}
		return null;
	}

	private static int clamp(long value) {
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
	}

	// Nest inside a caller's open transaction rather than throwing, since the request API is public
	// and a resolver may already hold one.
	private static Transaction open() {
		return Transaction.isOpen() ? Transaction.openNested(Transaction.getCurrentUnsafe()) : Transaction.openOuter();
	}

	/**
	 * Fabric storages are not slot indexed in general. A {@link SlottedStorage} has real slots. Any
	 * other storage is read as a snapshot of its non-empty views, taken on first read, and extracted
	 * from by resource rather than through a held view, because a view may go stale once the
	 * transaction that produced it closes. An empty non-slotted storage has no views at all but can
	 * still accept inserts, so insertion always goes to the storage itself.
	 */
	private static final class FabricStorage extends ExternalStorage {
		private final Storage<ItemVariant> storage;
		private List<StorageView<ItemVariant>> views;

		FabricStorage(Storage<ItemVariant> storage) {
			this.storage = storage;
		}

		private List<StorageView<ItemVariant>> views() {
			if (views == null) {
				views = new ArrayList<>();
				if (storage instanceof SlottedStorage<ItemVariant> slotted) {
					for (int i = 0; i < slotted.getSlotCount(); i++) views.add(slotted.getSlot(i));
				} else {
					for (StorageView<ItemVariant> view : storage.nonEmptyViews()) views.add(view);
				}
			}
			return views;
		}

		@Override
		public Object identity() {
			return storage;
		}

		@Override
		public int getContainerSize() {
			return views().size();
		}

		@Override
		protected ItemStack peek(int slot) {
			StorageView<ItemVariant> view = views().get(slot);
			if (view.isResourceBlank() || view.getAmount() <= 0) return ItemStack.EMPTY;
			return view.getResource().toStack(clamp(view.getAmount()));
		}

		@Override
		public int slotLimit(int slot, ItemStack stack) {
			return clamp(views().get(slot).getCapacity());
		}

		@Override
		public ItemStack insert(ItemStack stack, boolean simulate) {
			if (stack.isEmpty() || !storage.supportsInsertion()) return stack.copy();
			long inserted;
			try (Transaction tx = open()) {
				inserted = storage.insert(ItemVariant.of(stack), stack.getCount(), tx);
				if (!simulate) tx.commit();
			}
			return stack.copyWithCount(stack.getCount() - clamp(inserted));
		}

		@Override
		public ItemStack extract(int slot, int amount, boolean simulate) {
			if (amount <= 0) return ItemStack.EMPTY;
			StorageView<ItemVariant> view = views().get(slot);
			if (view.isResourceBlank()) return ItemStack.EMPTY;
			ItemVariant resource = view.getResource();
			int capped = Math.min(amount, resource.toStack().getMaxStackSize());
			long extracted;
			try (Transaction tx = open()) {
				extracted = storage instanceof SlottedStorage<ItemVariant>
						? view.extract(resource, capped, tx)
						: storage.extract(resource, capped, tx);
				if (!simulate) tx.commit();
			}
			if (!simulate && !(storage instanceof SlottedStorage<ItemVariant>)) views = null;
			return extracted <= 0 ? ItemStack.EMPTY : resource.toStack(clamp(extracted));
		}
	}
}
