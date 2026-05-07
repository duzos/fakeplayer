package dev.duzo.players.menu;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;

public record FakePlayerMenuProvider(FakePlayerEntity entity) implements MenuProvider {
	@Override
	public Component getDisplayName() {
		Component name = entity.getCustomName();
		return name != null ? name : Component.translatable("entity.players.fake_player");
	}

	@Nullable
	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
		return new FakePlayerMenu(containerId, playerInventory, entity);
	}
}
