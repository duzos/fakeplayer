package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.entities.FakeFishingHook;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;

/**
 * Registered so the spawned hook entity has a renderer (entities without one crash). Draws nothing
 * itself: the bobber and the line back to the fake's hand are drawn in world space by
 * {@link FishingLineRenderer}, which sidesteps the submit-node geometry pipeline.
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
	public void submit(EntityRenderState state, PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera) {
		// no-op
	}
}
