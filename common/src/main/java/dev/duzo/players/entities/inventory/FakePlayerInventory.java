package dev.duzo.players.entities.inventory;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.SimpleContainer;

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
}
