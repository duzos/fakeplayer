package dev.duzo.players.client.preview;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Draws a preview model straight into the screen. This version has no picture-in-picture, so the blocks
 * are submitted in the screen's own render pass, clipped to the preview rectangle by a scissor.
 */
public final class SchematicPreviewRenderer {
	private static final float TILT_DEGREES = 30f;
	private static final float FILL = 0.92f;
	/** Above the panel at z 0, and the model is at most half its box deep, so no part dips behind it. */
	private static final float DEPTH_OFFSET = 100f;

	private SchematicPreviewRenderer() {
	}

	public static void render(GuiGraphics graphics, SchematicPreviewModel model, float angle, int x0, int y0, int x1, int y1) {
		Minecraft minecraft = Minecraft.getInstance();
		float width = x1 - x0;
		float height = y1 - y0;
		float sx = model.sizeX();
		float sy = model.sizeY();
		float sz = model.sizeZ();
		// the bounding sphere, so no turn of the model can poke out of the box
		float diameter = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
		float fit = diameter <= 0 ? 1 : Math.min(width, height) * FILL / diameter;

		// anything already queued has to reach the screen before the scissor and depth state change
		graphics.flush();
		graphics.enableScissor(x0, y0, x1, y1);
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(515);
		RenderSystem.clear(256, Minecraft.ON_OSX);
		Lighting.setupFor3DItems();

		PoseStack pose = graphics.pose();
		pose.pushPose();
		pose.translate((x0 + x1) / 2f, (y0 + y1) / 2f, DEPTH_OFFSET);
		// the same handedness as a block item in a gui slot: y runs down the screen
		pose.scale(1f, -1f, 1f);
		pose.mulPose(Axis.XP.rotationDegrees(TILT_DEGREES));
		pose.mulPose(Axis.YP.rotationDegrees(angle));
		pose.scale(fit, fit, fit);
		pose.translate(-sx / 2f, -sy / 2f, -sz / 2f);

		MultiBufferSource.BufferSource buffers = graphics.bufferSource();
		BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
		for (int i = 0; i < model.visible(); i++) {
			pose.pushPose();
			pose.translate(model.x(i), model.y(i), model.z(i));
			blocks.renderSingleBlock(model.state(i), pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
			pose.popPose();
		}
		// not GuiGraphics.flush, which turns the depth test off and would let the far side of the model win
		buffers.endBatch();
		pose.popPose();

		RenderSystem.clear(256, Minecraft.ON_OSX);
		graphics.disableScissor();
	}
}
