package dev.duzo.players.entities.inventory;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

public class FakePlayerInventory extends SimpleContainer {
	public static final int SIZE = 35;

	private final FakePlayerEntity owner;
	private boolean loading;

	public FakePlayerInventory(FakePlayerEntity owner) {
		super(SIZE);
		this.owner = owner;
	}

	/** Trusted persistence path; gameplay insertion stays blocked until reconciliation. */
	@Override
	public void fromTag(ListTag tag) {
		loading = true;
		try { super.fromTag(tag); } finally { loading = false; }
	}

	public FakePlayerEntity getOwner() {
		return owner;
	}

	/** A vacant shell's items are with a possessing player, and anything put in now would be lost when they come back. */
	@Override
	public ItemStack addItem(ItemStack stack) {
		return !loading && (owner.isVacant() || owner.isUnreconciled()) ? stack : super.addItem(stack);
	}

	@Override
	public boolean canAddItem(ItemStack stack) {
		return !owner.isVacant() && !owner.isUnreconciled() && super.canAddItem(stack);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return !owner.isVacant() && !owner.isUnreconciled() && super.canPlaceItem(slot, stack);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (!loading && owner.isVacant() && !stack.isEmpty()) return;
		super.setItem(slot, stack);
	}
}
