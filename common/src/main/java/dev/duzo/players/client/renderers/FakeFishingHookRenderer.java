package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.duzo.players.entities.FakeFishingHook;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the vanilla fishing-bobber texture as a camera-facing quad plus a line from the owning
 * fake's hand to the bobber.
 * <p>
 * 26.2 removed the immediate-mode {@code MultiBufferSource}/world-render-hook pipeline this used on
 * 1.21.11 (the buffer-source types are gone entirely). Vanilla's own {@code FishingHookRenderer}
 * shows the replacement: draw it here, in the registered entity renderer's submit-node
 * {@code submit()}, via {@link SubmitNodeCollector#submitCustomGeometry}. That keeps working
 * identically on both loaders since entity submission is core vanilla dispatch, not a loader-specific
 * world-render event.
 */
public class FakeFishingHookRenderer extends EntityRenderer<FakeFishingHook, FakeFishingHookRenderer.State> {
	private static final Identifier BOBBER_TEXTURE = Identifier.parse("minecraft:textures/entity/fishing_hook.png");
	private static final int LINE_COLOR = 0xFF202020;
	private static final float LINE_WIDTH = 1.5F;
	private static final float BOBBER_HALF = 0.18F;
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final int SEGMENTS = 16;

	public FakeFishingHookRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(FakeFishingHook entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);

		float t = entity.tickCount + partialTick;
		double bob = Math.sin(t * 0.18) * 0.03;
		if (entity.isBiting()) bob -= 0.14 + Math.abs(Math.sin(t * 0.6)) * 0.05;
		state.bobY = (float) bob;

		Entity owner = entity.level().getEntity(entity.ownerId());
		if (owner instanceof LivingEntity living) {
			Vec3 hookPos = entity.getPosition(partialTick);
			state.handOffset = handPos(living, partialTick).subtract(hookPos);
		} else {
			state.handOffset = null;
		}
	}

	@Override
	public void submit(State state, PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.handOffset != null) {
			Vec3 lineEnd = state.handOffset;
			collector.submitCustomGeometry(matrices, RenderTypes.lines(),
					(pose, consumer) -> drawLine(pose, consumer, state.bobY, lineEnd));
		}
		collector.submitCustomGeometry(matrices, RenderTypes.entityCutout(BOBBER_TEXTURE),
				(pose, consumer) -> drawBobber(pose, consumer, camera.orientation, state.bobY));
	}

	// Mirrors vanilla FishingHookRenderer's third-person line origin: body yaw, main-hand lateral 0.35,
	// forward 0.8, vertical (eyeHeight - 0.45). The fake holds the rod in its right (main) hand -> i=+1.
	private static Vec3 handPos(LivingEntity owner, float partial) {
		double bodyYaw = Mth.lerp(partial, owner.yBodyRotO, owner.yBodyRot) * (Math.PI / 180.0);
		double sin = Math.sin(bodyYaw);
		double cos = Math.cos(bodyYaw);
		double lateral = 0.35; // main arm = RIGHT
		double x = Mth.lerp(partial, owner.xo, owner.getX()) - cos * lateral - sin * 0.8;
		double y = Mth.lerp(partial, owner.yo, owner.getY()) + owner.getEyeHeight() - 0.45; // vanilla eyeHeight-0.45
		double z = Mth.lerp(partial, owner.zo, owner.getZ()) - sin * lateral + cos * 0.8;
		if (owner instanceof dev.duzo.players.entities.FakePlayerEntity fp && fp.isSitting()) y -= 0.3; // seated: drop a little (raised per feedback)
		return new Vec3(x, y, z);
	}

	// All coordinates here are local to the hook's submit-node pose (already positioned/camera-relative
	// by the entity render dispatcher), not world/camera-relative like the old buffer-source version.
	private static void drawLine(PoseStack.Pose pose, VertexConsumer lines, float bobY, Vec3 handOffset) {
		Matrix4f mat = pose.pose();
		Vec3 from = new Vec3(0, bobY, 0);
		Vec3 to = handOffset;
		double sag = Math.min(0.6, from.distanceTo(to) * 0.12);
		int r = (LINE_COLOR >> 16) & 0xFF, g = (LINE_COLOR >> 8) & 0xFF, b = LINE_COLOR & 0xFF, a = (LINE_COLOR >> 24) & 0xFF;
		Vec3 prev = null;
		for (int i = 0; i <= SEGMENTS; i++) {
			float f = i / (float) SEGMENTS;
			double x = Mth.lerp(f, from.x, to.x);
			double y = Mth.lerp(f, from.y, to.y) - sag * (4 * f * (1 - f));
			double z = Mth.lerp(f, from.z, to.z);
			Vec3 cur = new Vec3(x, y, z);
			if (prev != null) {
				Vec3 d = cur.subtract(prev).normalize();
				lines.addVertex(mat, (float) prev.x, (float) prev.y, (float) prev.z).setColor(r, g, b, a).setNormal((float) d.x, (float) d.y, (float) d.z).setLineWidth(LINE_WIDTH);
				lines.addVertex(mat, (float) cur.x, (float) cur.y, (float) cur.z).setColor(r, g, b, a).setNormal((float) d.x, (float) d.y, (float) d.z).setLineWidth(LINE_WIDTH);
			}
			prev = cur;
		}
	}

	private static void drawBobber(PoseStack.Pose pose, VertexConsumer buf, Quaternionf camRot, float bobY) {
		Matrix4f mat = pose.pose();
		Vec3 c = new Vec3(0, bobY, 0);
		Vector3f right = camRot.transform(new Vector3f(1, 0, 0));
		Vector3f up = camRot.transform(new Vector3f(0, 1, 0));
		Vector3f normal = camRot.transform(new Vector3f(0, 0, 1));
		quadVertex(buf, mat, c, right, up, -1, -1, 0, 1, normal);
		quadVertex(buf, mat, c, right, up, 1, -1, 1, 1, normal);
		quadVertex(buf, mat, c, right, up, 1, 1, 1, 0, normal);
		quadVertex(buf, mat, c, right, up, -1, 1, 0, 0, normal);
	}

	private static void quadVertex(VertexConsumer buf, Matrix4f mat, Vec3 c, Vector3f right, Vector3f up,
	                               float sx, float sy, float u, float v, Vector3f normal) {
		float x = (float) c.x + (right.x * sx + up.x * sy) * BOBBER_HALF;
		float y = (float) c.y + (right.y * sx + up.y * sy) * BOBBER_HALF;
		float z = (float) c.z + (right.z * sx + up.z * sy) * BOBBER_HALF;
		buf.addVertex(mat, x, y, z)
			.setColor(255, 255, 255, 255)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(FULL_BRIGHT)
			.setNormal(normal.x, normal.y, normal.z);
	}

	public static class State extends EntityRenderState {
		Vec3 handOffset;
		float bobY;
	}
}
