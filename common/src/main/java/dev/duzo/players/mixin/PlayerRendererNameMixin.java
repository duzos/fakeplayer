package dev.duzo.players.mixin;

import dev.duzo.players.client.PossessionDisguises;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The name tag of a possessing player shows the fake's name, to match the skin. */
@Mixin(PlayerRenderer.class)
public class PlayerRendererNameMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V", at = @At("TAIL"))
	private void players$disguiseName(AbstractClientPlayer player, PlayerRenderState state, float partialTick, CallbackInfo ci) {
		if (state.nameTag == null) return;
		String name = PossessionDisguises.nameFor(player.getUUID());
		if (name != null) state.nameTag = Component.literal(name);
	}
}
