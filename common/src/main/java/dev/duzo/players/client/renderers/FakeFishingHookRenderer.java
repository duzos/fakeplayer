package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.entities.FakeFishingHook;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Registered so the hook entity has a renderer (entities without one crash). Draws nothing itself:
 * the bobber and the line to the fake's hand are drawn in world space by {@link FishingLineRenderer}.
 */
public class FakeFishingHookRenderer extends EntityRenderer<FakeFishingHook, EntityRenderState> {
	public FakeFishingHookRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public EntityRenderState createRenderState() {
		return new EntityRenderState();
	}

	@Override
	public void render(EntityRenderState state, PoseStack matrices, MultiBufferSource buffers, int light) {
		// no-op
	}
}
