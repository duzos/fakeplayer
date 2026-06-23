package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.entities.FakeFishingHook;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * Registered so the hook entity has a renderer (entities without one crash). Draws nothing itself:
 * the bobber and the line to the fake's hand are drawn in world space by {@link FishingLineRenderer}.
 */
public class FakeFishingHookRenderer extends EntityRenderer<FakeFishingHook> {
	private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/entity/fishing_hook.png");

	public FakeFishingHookRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public void render(FakeFishingHook entity, float yaw, float partialTick, PoseStack matrices, MultiBufferSource buffers, int light) {
		// no-op
	}

	@Override
	public ResourceLocation getTextureLocation(FakeFishingHook entity) {
		return TEXTURE;
	}
}
