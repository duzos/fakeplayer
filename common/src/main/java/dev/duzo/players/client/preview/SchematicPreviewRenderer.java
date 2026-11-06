package dev.duzo.players.client.preview;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.jspecify.annotations.Nullable;

public class SchematicPreviewRenderer extends PictureInPictureRenderer<SchematicPreviewRenderState> {
	private static final float TILT_DEGREES = 30f;
	private static final float FILL = 0.92f;

	private @Nullable SchematicPreviewModel renderedModel;
	private int renderedAngle = -1;

	public SchematicPreviewRenderer(MultiBufferSource.BufferSource bufferSource) {
		super(bufferSource);
	}

	@Override
	public Class<SchematicPreviewRenderState> getRenderStateClass() {
		return SchematicPreviewRenderState.class;
	}

	@Override
	protected boolean textureIsReadyToBlit(SchematicPreviewRenderState state) {
		return state.model() == renderedModel && state.angle() == renderedAngle;
	}

	@Override
	protected void renderToTexture(SchematicPreviewRenderState state, PoseStack pose) {
		SchematicPreviewModel model = state.model();
		renderedModel = model;
		renderedAngle = state.angle();

		Minecraft minecraft = Minecraft.getInstance();
		minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);

		float width = state.x1() - state.x0();
		float height = state.y1() - state.y0();
		float sx = model.sizeX();
		float sy = model.sizeY();
		float sz = model.sizeZ();
		// the bounding sphere, so no turn of the model can poke out of the box
		float diameter = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
		float fit = diameter <= 0 ? 1 : Math.min(width, height) * FILL / diameter;

		// the same net handedness as a block item in a gui slot
		pose.scale(1f, -1f, -1f);
		pose.mulPose(Axis.XP.rotationDegrees(TILT_DEGREES));
		pose.mulPose(Axis.YP.rotationDegrees(state.angle()));
		pose.scale(fit, fit, fit);
		pose.translate(-sx / 2f, -sy / 2f, -sz / 2f);

		BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
		for (int i = 0; i < model.visible(); i++) {
			pose.pushPose();
			pose.translate(model.x(i), model.y(i), model.z(i));
			blocks.renderSingleBlock(model.state(i), pose, this.bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
			pose.popPose();
		}
	}

	@Override
	protected String getTextureLabel() {
		return "fp_schematic_preview";
	}

	@Override
	public void close() {
		renderedModel = null;
		super.close();
	}
}
