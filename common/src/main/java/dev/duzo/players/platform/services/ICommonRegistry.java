package dev.duzo.players.platform.services;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
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
}
