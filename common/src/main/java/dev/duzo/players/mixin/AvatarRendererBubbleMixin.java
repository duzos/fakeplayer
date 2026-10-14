package dev.duzo.players.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.renderers.FakeAvatarRenderState;
import dev.duzo.players.client.renderers.SpeechBubbles;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The entity render dispatcher hands every AvatarRenderState to vanilla's player renderer, so a fake is
 * drawn by AvatarRenderer and never reaches the mod's own renderer's submit. Bubbles ride along here.
 */
@Mixin(AvatarRenderer.class)
public class AvatarRendererBubbleMixin {
	@Inject(method = "submitNameTag(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("TAIL"))
	private void players$speechBubbles(AvatarRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (state instanceof FakeAvatarRenderState fake) SpeechBubbles.submit(fake, pose, collector, camera);
	}
}
