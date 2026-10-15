package dev.duzo.players.entities.inventory;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

public class FakePlayerInventory extends SimpleContainer {
	public static final int SIZE = 35;

	private final FakePlayerEntity owner;

	public FakePlayerInventory(FakePlayerEntity owner) {
		super(SIZE);
		this.owner = owner;
	}

	public FakePlayerEntity getOwner() {
		return owner;
	}

	/** A vacant shell's items are with a possessing player, and anything put in now would be lost when they come back. */
	@Override
	public ItemStack addItem(ItemStack stack) {
		return owner.isVacant() ? stack : super.addItem(stack);
	}

	@Override
	public boolean canAddItem(ItemStack stack) {
		return !owner.isVacant() && super.canAddItem(stack);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return !owner.isVacant() && super.canPlaceItem(slot, stack);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (owner.isVacant() && !stack.isEmpty()) return;
		super.setItem(slot, stack);
	}
}
