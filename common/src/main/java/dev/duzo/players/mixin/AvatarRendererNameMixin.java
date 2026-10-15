package dev.duzo.players.mixin;

import dev.duzo.players.client.PossessionDisguises;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The name tag of a possessing player shows the fake's name, to match the skin. */
@Mixin(AvatarRenderer.class)
public class AvatarRendererNameMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void players$disguiseName(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (state.nameTag == null || !(entity instanceof AbstractClientPlayer player)) return;
		String name = PossessionDisguises.nameFor(player.getUUID());
		if (name != null) state.nameTag = Component.literal(name);
	}
}
