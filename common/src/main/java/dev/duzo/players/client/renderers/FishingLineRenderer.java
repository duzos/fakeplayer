package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.duzo.players.entities.FakeFishingHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws every {@link FakeFishingHook}: the vanilla fishing-bobber texture as a camera-facing quad
 * plus a line from the owning fake's hand to the bobber. Runs from the same world-render hook that
 * drives {@link SessionItemRenderer}, so it stays off the entity submit-node pipeline.
 */
public final class FishingLineRenderer {
	private static final Identifier BOBBER_TEXTURE = Identifier.parse("minecraft:textures/entity/fishing_hook.png");
	private static final int LINE_COLOR = 0xFF202020;
	private static final float LINE_WIDTH = 1.5F;
	private static final float BOBBER_HALF = 0.18F;
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final int SEGMENTS = 16;

	private FishingLineRenderer() {}

	public static void render(PoseStack pose) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;

		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		Quaternionf camRot = mc.gameRenderer.getMainCamera().rotation();
		MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof FakeFishingHook hook)) continue;
			Vec3 bobber = bobberPos(hook, partial).subtract(cam);

			Entity owner = level.getEntity(hook.ownerId());
			if (owner != null) {
				Vec3 hand = handPos(owner, partial).subtract(cam);
				drawLine(pose, buffers.getBuffer(RenderTypes.lines()), hand, bobber);
			}
			drawBobber(pose, buffers.getBuffer(RenderTypes.entityCutoutNoCull(BOBBER_TEXTURE)), camRot, bobber);
		}

		buffers.endBatch(RenderTypes.lines());
		buffers.endBatch(RenderTypes.entityCutoutNoCull(BOBBER_TEXTURE));
	}

	private static Vec3 bobberPos(FakeFishingHook hook, float partial) {
		Vec3 base = hook.getPosition(partial);
		float t = hook.tickCount + partial;
		double bob = Math.sin(t * 0.18) * 0.03;
		if (hook.isBiting()) bob -= 0.14 + Math.abs(Math.sin(t * 0.6)) * 0.05;
		return base.add(0, bob, 0);
	}

	private static Vec3 handPos(Entity owner, float partial) {
		double yaw = Math.toRadians(owner.getViewYRot(partial));
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 right = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		return owner.getEyePosition(partial).add(right.scale(0.35)).add(forward.scale(0.3)).add(0, -0.2, 0);
	}

	private static void drawLine(PoseStack pose, VertexConsumer lines, Vec3 from, Vec3 to) {
		Matrix4f mat = pose.last().pose();
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

	private static void drawBobber(PoseStack pose, VertexConsumer buf, Quaternionf camRot, Vec3 c) {
		Matrix4f mat = pose.last().pose();
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
}
