package dev.duzo.players.client.preview;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.texture.OverlayTexture;
import java.lang.ref.WeakReference;
import org.jspecify.annotations.Nullable;

public class SchematicPreviewRenderer extends PictureInPictureRenderer<SchematicPreviewRenderState> {
	private static final float TILT_DEGREES = 30f;
	private static final float FILL = 0.92f;

	// weak, so the renderer that outlives every picker does not keep the last model alive
	private @Nullable WeakReference<SchematicPreviewModel> renderedModel;
	private int renderedAngle = -1;

	private @Nullable BlockModelResolver resolver;
	// resolved once per model, dropped when the model or the loaded block models change (resource reload)
	private final Map<BlockState, BlockModelRenderState> resolved = new IdentityHashMap<>();
	private @Nullable WeakReference<SchematicPreviewModel> resolvedFor;
	private @Nullable Object resolvedAgainst;

	@Override
	public Class<SchematicPreviewRenderState> getRenderStateClass() {
		return SchematicPreviewRenderState.class;
	}

	@Override
	protected boolean textureIsReadyToBlit(SchematicPreviewRenderState state) {
		return renderedModel != null && state.model() == renderedModel.get() && state.angle() == renderedAngle;
	}

	@Override
	protected void renderToTexture(SchematicPreviewRenderState state, PoseStack pose, SubmitNodeCollector collector) {
		SchematicPreviewModel model = state.model();
		renderedModel = new WeakReference<>(model);
		renderedAngle = state.angle();

		Minecraft minecraft = Minecraft.getInstance();
		minecraft.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);

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
		pose.rotateDegrees(Axis.XP, TILT_DEGREES);
		pose.rotateDegrees(Axis.YP, state.angle());
		pose.scale(fit, fit, fit);
		pose.translate(-sx / 2f, -sy / 2f, -sz / 2f);

		if (resolver == null) resolver = new BlockModelResolver(minecraft.getModelManager());
		Object blockModels = minecraft.getModelManager().getBlockModelSet();
		if (resolvedFor == null || resolvedFor.get() != model || resolvedAgainst != blockModels) {
			resolved.clear();
			resolvedFor = new WeakReference<>(model);
			resolvedAgainst = blockModels;
		}
		for (int i = 0; i < model.visible(); i++) {
			BlockState blockState = model.state(i);
			BlockModelRenderState renderState = resolved.get(blockState);
			if (renderState == null) {
				renderState = new BlockModelRenderState();
				resolver.update(renderState, blockState, BlockDisplayContext.create());
				resolved.put(blockState, renderState);
			}
			if (renderState.isEmpty()) continue;
			pose.pushPose();
			pose.translate(model.x(i), model.y(i), model.z(i));
			renderState.submit(pose, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
	}

	@Override
	protected float getTranslateY(int height, int guiScale) {
		return height / 2f;
	}

	@Override
	protected String getTextureLabel() {
		return "fp_schematic_preview";
	}

	@Override
	public void close() {
		renderedModel = null;
		resolvedFor = null;
		resolvedAgainst = null;
		resolved.clear();
		super.close();
	}
}
