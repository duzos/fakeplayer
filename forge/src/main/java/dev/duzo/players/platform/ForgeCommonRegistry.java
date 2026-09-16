package dev.duzo.players.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.duzo.players.Constants;
import dev.duzo.players.platform.services.ICommonRegistry;
import dev.duzo.players.platform.services.ICustomRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
public class ForgeCommonRegistry implements ICommonRegistry {
	public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Constants.MOD_ID);
	public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Constants.MOD_ID);
	public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Constants.MOD_ID);
	public static final HashMap<Supplier<? extends EntityType<?>>, Supplier<AttributeSupplier.Builder>> ATTRIBUTES = new HashMap<>();
	public static final HashMap<ResourceKey<CreativeModeTab>, List<Supplier<Item>>> GROUPS = new HashMap<>();
	public static final List<Consumer<CommandDispatcher<CommandSourceStack>>> COMMANDS = new ArrayList<>();
	private static final List<ForgeCustomRegistry<?>> CUSTOM = new ArrayList<>();

	public static void init(IEventBus bus) {
		ITEMS.register(bus);
		ENTITIES.register(bus);
		MENUS.register(bus);
		CUSTOM.forEach(custom -> custom.deferred.register(bus));
		bus.addListener(ForgeCommonRegistry::newRegistries);
	}

	private static void newRegistries(NewRegistryEvent e) {
		CUSTOM.forEach(custom -> e.register(custom.registry));
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

	@Override
	public <T extends AbstractContainerMenu> Supplier<MenuType<T>> registerMenu(String modid, String name, ExtendedMenuFactory<T> factory) {
		return MENUS.register(name, () -> IMenuTypeExtension.create(factory::create));
	}

	@Override
	public void openMenu(ServerPlayer player, MenuProvider provider, Consumer<FriendlyByteBuf> data) {
		player.openMenu(provider, buf -> data.accept(buf));
	}

	@Override
	public <T> ICustomRegistry<T> createRegistry(ResourceKey<Registry<T>> key) {
		ForgeCustomRegistry<T> custom = new ForgeCustomRegistry<>(
				new RegistryBuilder<>(key).sync(true).create(),
				DeferredRegister.create(key, Constants.MOD_ID));
		CUSTOM.add(custom);
		return custom;
	}

	private static final class ForgeCustomRegistry<T> implements ICustomRegistry<T> {
		private final Registry<T> registry;
		private final DeferredRegister<T> deferred;

		private ForgeCustomRegistry(Registry<T> registry, DeferredRegister<T> deferred) {
			this.registry = registry;
			this.deferred = deferred;
		}

		@Override
		public Supplier<T> register(String modid, String name, Supplier<T> value) {
			return deferred.register(name, value);
		}

		@Nullable
		@Override
		public T get(ResourceLocation id) {
			return registry.get(id);
		}
	}
}
