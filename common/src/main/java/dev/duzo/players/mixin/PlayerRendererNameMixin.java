package dev.duzo.players.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.PossessionDisguises;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The name tag of a possessing player shows the fake's name, to match the skin. */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererNameMixin {
	@Unique
	private boolean players$drawingDisguised;

	@Shadow
	protected abstract void renderNameTag(AbstractClientPlayer player, Component name, PoseStack stack, MultiBufferSource buffer, int light);

	@Inject(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("HEAD"), cancellable = true)
	private void players$disguiseName(AbstractClientPlayer player, Component name, PoseStack stack, MultiBufferSource buffer, int light, CallbackInfo ci) {
		if (this.players$drawingDisguised) return;
		String disguised = PossessionDisguises.nameFor(player.getUUID());
		if (disguised == null) return;
		this.players$drawingDisguised = true;
		try {
			this.renderNameTag(player, Component.literal(disguised), stack, buffer, light);
		} finally {
			this.players$drawingDisguised = false;
		}
		ci.cancel();
	}
}
