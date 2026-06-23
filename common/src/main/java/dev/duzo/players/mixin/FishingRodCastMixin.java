package dev.duzo.players.mixin;

import dev.duzo.players.entities.FakeFishingHook;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.FishingRodCast;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's fishing-rod "cast" model only triggers for a real Player with an active hook. A fake
 * player is a PathfinderMob, so this makes its held rod show the cast model while it has a bobber out.
 */
@Mixin(FishingRodCast.class)
public class FishingRodCastMixin {
	@Inject(method = "get", at = @At("HEAD"), cancellable = true)
	private void players$fakeCast(ItemStack stack, ClientLevel level, LivingEntity entity, int seed, ItemDisplayContext ctx, CallbackInfoReturnable<Boolean> cir) {
		if (level == null || !(entity instanceof FakePlayerEntity fake)) return;
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof FakeFishingHook hook && hook.ownerId() == fake.getId()) {
				cir.setReturnValue(true);
				return;
			}
		}
	}
}
