package dev.duzo.players.mixin;

import net.minecraft.client.renderer.item.ClampedItemPropertyFunction;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the (private on this version) ItemProperties.register so we can add the rod "cast" predicate. */
@Mixin(value = ItemProperties.class, remap = false)
public interface ItemPropertiesAccessor {
	@Invoker("register")
	static void players$register(Item item, ResourceLocation id, ClampedItemPropertyFunction function) {
		throw new AssertionError();
	}
}
