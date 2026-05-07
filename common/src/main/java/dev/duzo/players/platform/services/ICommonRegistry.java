package dev.duzo.players.platform.services;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
<<<<<<< HEAD
import net.minecraft.resources.Identifier;
=======
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
>>>>>>> f86782c (feat(gui): add fake player management gui (#22))
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface ICommonRegistry {
	<T extends Item> Supplier<T> registerItem(String modid, String name, Supplier<T> item);

	<T extends Entity> Supplier<EntityType<T>> registerEntity(String modid, String name, Supplier<EntityType<T>> entity);

	<T extends LivingEntity> void registerAttributes(Supplier<EntityType<T>> entity, Supplier<AttributeSupplier.Builder> attributes);

	<T extends Item> void addToGroup(Supplier<T> item, ResourceKey<CreativeModeTab> tab);

	default <T extends Item> void addToGroup(Supplier<T> item, String tab) {
		addToGroup(item, ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.parse(tab)));
	}

	void registerCommand(Consumer<CommandDispatcher<CommandSourceStack>> command);

	<T extends AbstractContainerMenu> Supplier<MenuType<T>> registerMenu(String modid, String name, ExtendedMenuFactory<T> factory);

	void openMenu(ServerPlayer player, MenuProvider provider, Consumer<FriendlyByteBuf> data);

	interface ExtendedMenuFactory<T extends AbstractContainerMenu> {
		T create(int containerId, Inventory playerInventory, FriendlyByteBuf extraData);
	}
}
