package dev.duzo.players.client.preview;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Draws a preview straight into the screen, since this version has no picture-in-picture pass. The
 * order mirrors how vanilla draws a block item in a slot: flush what is queued, set the 3D item
 * lighting, render into the shared buffer source at a raised z, flush again.
 */
public final class SchematicPreviewRenderer {
	private static final float TILT_DEGREES = 30f;
	private static final float FILL = 0.92f;
	/** Vanilla draws gui items at 150, above the panel and below tooltips and decorations at 200 and up. */
	private static final float Z = 100f;

	private SchematicPreviewRenderer() {
	}

	public static void render(GuiGraphics ctx, SchematicPreviewModel model, int angle, int x0, int y0, int x1, int y1) {
		Minecraft minecraft = Minecraft.getInstance();
		float width = x1 - x0;
		float height = y1 - y0;
		float sx = model.sizeX();
		float sy = model.sizeY();
		float sz = model.sizeZ();
		// the bounding sphere, so no turn of the model can poke out of the box
		float diameter = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
		float fit = diameter <= 0 ? 1 : Math.min(width, height) * FILL / diameter;

		// enableScissor flushes what was queued before it, so the panel is already drawn unclipped
		ctx.enableScissor(x0, y0, x1, y1);
		PoseStack pose = ctx.pose();
		pose.pushPose();
		pose.translate((x0 + x1) / 2f, (y0 + y1) / 2f, Z);
		// gui y runs down, so this flip is the same one vanilla applies to a block item
		pose.scale(1f, -1f, 1f);
		pose.mulPose(Axis.XP.rotationDegrees(TILT_DEGREES));
		pose.mulPose(Axis.YP.rotationDegrees(angle));
		pose.scale(fit, fit, fit);
		pose.translate(-sx / 2f, -sy / 2f, -sz / 2f);

		MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
		Lighting.setupFor3DItems();
		BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
		for (int i = 0; i < model.visible(); i++) {
			pose.pushPose();
			pose.translate(model.x(i), model.y(i), model.z(i));
			blocks.renderSingleBlock(model.state(i), pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
			pose.popPose();
		}
		// drawn before the scissor comes off, or the batch would land unclipped
		ctx.flush();
		pose.popPose();
		ctx.disableScissor();
	}
}
