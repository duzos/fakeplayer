package dev.duzo.players.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.duzo.players.Constants;
import dev.duzo.players.platform.services.ICommonRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
public class ForgeCommonRegistry implements ICommonRegistry {
	public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Constants.MOD_ID);
	public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Constants.MOD_ID);
	public static final HashMap<Supplier<? extends EntityType<?>>, Supplier<AttributeSupplier.Builder>> ATTRIBUTES = new HashMap<>();
	public static final HashMap<ResourceKey<CreativeModeTab>, List<Supplier<Item>>> GROUPS = new HashMap<>();
	public static final List<Consumer<CommandDispatcher<CommandSourceStack>>> COMMANDS = new ArrayList<>();

	public static void init(IEventBus bus) {
		ITEMS.register(bus);
		ENTITIES.register(bus);
	}

	@Override
	public <T extends Item> Supplier<T> registerItem(String modid, String name, Supplier<T> item) {
		return ITEMS.register(name, item);
	}

	@SubscribeEvent
	public static void createAttributes(EntityAttributeCreationEvent e) {
		ATTRIBUTES.forEach((entity, attributes) -> e.put((EntityType<? extends LivingEntity>) entity.get(), attributes.get().build()));
	}

	@Override
	public <T extends Entity> Supplier<EntityType<T>> registerEntity(String modid, String name, Supplier<EntityType<T>> entity) {
		return ENTITIES.register(name, entity);
	}

	@Override
	public <T extends LivingEntity> void registerAttributes(Supplier<EntityType<T>> entity, Supplier<AttributeSupplier.Builder> attributes) {
		ATTRIBUTES.put(entity, attributes);
	}

	@SubscribeEvent
	public static void addGroups(BuildCreativeModeTabContentsEvent e) {
		if (!GROUPS.containsKey(e.getTabKey())) {
			return;
		}

		GROUPS.get(e.getTabKey()).forEach(item -> e.accept(item.get()));
	}

	@Override
	public <T extends Item> void addToGroup(Supplier<T> item, ResourceKey<CreativeModeTab> tab) {
		if (!GROUPS.containsKey(tab)) {
			GROUPS.put(tab, new ArrayList<>());
		}

		GROUPS.get(tab).add((Supplier<Item>) item);
	}

	@Override
	public void registerCommand(Consumer<CommandDispatcher<CommandSourceStack>> command) {
		COMMANDS.add(command);
	}
}
