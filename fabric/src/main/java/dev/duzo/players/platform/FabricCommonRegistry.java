package dev.duzo.players.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.duzo.players.platform.services.ICommonRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class FabricCommonRegistry implements ICommonRegistry {

	private static <T, R extends Registry<? super T>> Supplier<T> registerSupplier(R registry, String modid, String id, Supplier<T> object) {
		final T registeredObject = Registry.register((Registry<T>) registry,
				ResourceLocation.fromNamespaceAndPath(modid, id), object.get());

		return () -> registeredObject;
	}

	@Override
	public <T extends Item> Supplier<T> registerItem(String modid, String name, Supplier<T> item) {
		return registerSupplier(BuiltInRegistries.ITEM, modid, name, item);
	}

	@Override
	public <T extends Entity> Supplier<EntityType<T>> registerEntity(String modid, String name, Supplier<EntityType<T>> entity) {
		return registerSupplier(BuiltInRegistries.ENTITY_TYPE, modid, name, entity);
	}

	@Override
	public <T extends LivingEntity> void registerAttributes(Supplier<EntityType<T>> entity, Supplier<AttributeSupplier.Builder> attributes) {
		FabricDefaultAttributeRegistry.register(entity.get(), attributes.get().build());
	}

	@Override
	public <T extends Item> void addToGroup(Supplier<T> item, ResourceKey<CreativeModeTab> tab) {
		ItemGroupEvents.modifyEntriesEvent(tab).register((group) -> {
			group.accept(item.get());
		});
	}

	@Override
	public void registerCommand(Consumer<CommandDispatcher<CommandSourceStack>> command) {
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
			command.accept(dispatcher);
		});
	}
}
