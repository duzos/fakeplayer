package dev.duzo.players.client.renderers;

import dev.duzo.players.entities.FakeFishingHook;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.mixin.ItemPropertiesAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Items;

/**
 * Pre-1.21.4 versions have no FishingRodCast item-model condition. We re-register the vanilla rod
 * "cast" predicate (preserving real-player behaviour) and add the case where a fake player has a
 * bobber out, so its held rod shows the bent cast model.
 */
public final class LegacyRodCast {
	private LegacyRodCast() {}

	public static void register() {
		ItemPropertiesAccessor.players$register(Items.FISHING_ROD, new ResourceLocation("minecraft", "cast"),
			(stack, level, entity, seed) -> {
				if (entity == null) return 0.0F;
				boolean main = entity.getMainHandItem() == stack;
				boolean off = entity.getOffhandItem() == stack;
				if (entity.getMainHandItem().getItem() instanceof FishingRodItem) off = false;
				if (!main && !off) return 0.0F;
				if (entity instanceof Player player) return player.fishing != null ? 1.0F : 0.0F;
				if (entity instanceof FakePlayerEntity fake && level != null) {
					for (Entity e : level.entitiesForRendering()) {
						if (e instanceof FakeFishingHook hook && hook.ownerId() == fake.getId()) return 1.0F;
					}
				}
				return 0.0F;
			});
	}
}
