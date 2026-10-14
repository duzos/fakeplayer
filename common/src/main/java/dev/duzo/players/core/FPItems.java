package dev.duzo.players.core;

import dev.duzo.players.Constants;
import dev.duzo.players.platform.Services;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

public class FPItems {
	public static final Supplier<Item> PLAYER_AI = register("player_ai", () -> new Item(properties("player_ai")));
	public static final Supplier<Item> PLAYER_SHELL = register("player_shell", () -> new Item(properties("player_shell")));
	public static final Supplier<Item> PLAYER_EGG = register("player_egg", () -> new PlayerEggItem(properties("player_egg")));
	public static final Supplier<Item> AI_MARKER = register("ai_marker", () -> new AIMarkerItem(properties("ai_marker")));
	public static final Supplier<Item> CHUNK_ANCHOR = register("chunk_anchor", () -> new ChunkAnchorItem(properties("chunk_anchor").stacksTo(1).fireResistant()));
	public static final Supplier<Item> POSSESSION_LINK = register("possession_link", () -> new PossessionLinkItem(properties("possession_link").durability(PossessionLinkItem.DURABILITY).fireResistant()));

	public static Supplier<Item> register(String name, Supplier<Item> item) {
		return Services.COMMON_REGISTRY.registerItem(Constants.MOD_ID, name, item);
	}

	private static Item.Properties properties(String name) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
		return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
	}

	public static void init() {
		Services.COMMON_REGISTRY.addToGroup(PLAYER_AI, "ingredients");
		Services.COMMON_REGISTRY.addToGroup(PLAYER_SHELL, "ingredients");
		Services.COMMON_REGISTRY.addToGroup(PLAYER_EGG, "ingredients");
		Services.COMMON_REGISTRY.addToGroup(CHUNK_ANCHOR, "ingredients");
		Services.COMMON_REGISTRY.addToGroup(POSSESSION_LINK, "ingredients");
	}
}
