package dev.duzo.players.entities.ai.storage;

import dev.duzo.players.Constants;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A modded inventory, reached through the loader's item transfer API, dressed as a {@link Container}
 * so every read path (indexing, filters, counting) works unchanged.
 *
 * <p>Reads hand out copies. Writes must go through {@link #insert} and {@link #extract}, which return
 * what actually moved: a modded storage can refuse or cap an insertion (filters, locks, per-slot
 * limits) and a copy-then-{@code setItem} write would silently lose or duplicate items. Callers reach
 * these through {@code JobHelpers.insert} and {@code JobHelpers.extract}, never directly.
 */
public abstract class ExternalStorage implements Container {

	private static boolean warnedSetItem;

	/** The underlying handler, used to tell two lookups of one inventory apart from two inventories. */
	public abstract Object identity();

	/** A copy of the slot's contents. Mutating it changes nothing. */
	protected abstract ItemStack peek(int slot);

	/** How many of {@code stack} this slot can hold in total, which for a drawer is far above 64. */
	public abstract int slotLimit(int slot, ItemStack stack);

	/** Insert into any slot. Returns the part that did not fit. Never mutates {@code stack}. */
	public abstract ItemStack insert(ItemStack stack, boolean simulate);

	/**
	 * Take up to {@code amount} from one slot, but never more than one normal stack: a drawer slot can
	 * hold thousands, and an oversized stack is silently trimmed by any vanilla container it lands in.
	 * Returns what was taken.
	 */
	public abstract ItemStack extract(int slot, int amount, boolean simulate);

	@Override
	public ItemStack getItem(int slot) {
		return peek(slot);
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < getContainerSize(); i++) if (!peek(i).isEmpty()) return false;
		return true;
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		return extract(slot, amount, false);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return extract(slot, peek(slot).getCount(), false);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		// Any caller reaching this is a missed transfer path, and honouring it could dupe or delete.
		if (!warnedSetItem) {
			warnedSetItem = true;
			Constants.LOG.error("Ignored a direct write to modded storage; this is a Fake Players bug", new IllegalStateException());
		}
	}

	@Override
	public int getMaxStackSize() {
		return Integer.MAX_VALUE;
	}

	@Override
	public void setChanged() {
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void clearContent() {
	}
}
