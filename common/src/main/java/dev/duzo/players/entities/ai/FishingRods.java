package dev.duzo.players.entities.ai;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Rod detection for the Fisherman. {@link FishingRodItem} is the load-bearing half: no vanilla item
 * other than the fishing rod extends it, and modded rods almost always do. The tags are a safety net
 * for rods that do not, and differ by loader and version; a tag that does not exist matches nothing,
 * so listing all of them is safe everywhere.
 */
public final class FishingRods {
	private FishingRods() {}

	private static final List<TagKey<Item>> ROD_TAGS = List.of(
			tag("minecraft", "enchantable/fishing"), // vanilla, 1.20.5+
			tag("c", "tools/fishing_rod"),           // Fabric API
			tag("c", "tools/fishing_rods"),
			tag("forge", "tools/fishing_rods")       // Forge 1.20.1
	);

	public static boolean isFishingRod(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (stack.getItem() instanceof FishingRodItem) return true;
		for (TagKey<Item> tag : ROD_TAGS) if (stack.is(tag)) return true;
		return false;
	}

	private static TagKey<Item> tag(String namespace, String path) {
		return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(namespace, path));
	}
}
