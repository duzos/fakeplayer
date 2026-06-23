package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.duzo.players.entities.FakeFishingHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Draws every {@link FakeFishingHook} in the world: a line from the owning fake's hand to the
 * bobber, plus the bobber itself. Runs from the same world-render hook that drives
 * {@link SessionItemRenderer}, so it avoids the entity submit-node pipeline entirely.
 */
public final class FishingLineRenderer {
	private static final int LINE_COLOR = 0xFFEFEFEF;
	private static final int BOBBER_COLOR = 0xFFE24A4A;
	private static final float LINE_WIDTH = 2.0F;
	private static final float BOBBER_SIZE = 0.14F;
	private static final float LINE_BEAD = 0.04F;
	private static final int SEGMENTS = 24;

	private FishingLineRenderer() {}

	public static void render(PoseStack pose) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;

		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
		boolean drewAny = false;

		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof FakeFishingHook hook)) continue;
			Entity owner = level.getEntity(hook.ownerId());
			Vec3 bobber = bobberPos(hook, partial);

			if (owner != null) {
				Vec3 hand = handPos(owner, partial);
				drawLine(pose, lines, cam, hand, bobber);
			}
			drawBobber(pose, lines, cam, bobber);
			drewAny = true;
		}

		if (drewAny) buffers.endBatch(RenderTypes.lines());
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

	// Drawn as a string of small boxes via the proven ShapeRenderer path (RenderTypes.lines()
	// has a per-vertex LineWidth element that hand-rolled vertices can't satisfy here).
	private static void drawLine(PoseStack pose, VertexConsumer lines, Vec3 cam, Vec3 from, Vec3 to) {
		double sag = Math.min(0.6, from.distanceTo(to) * 0.12);
		for (int i = 1; i < SEGMENTS; i++) {
			float f = i / (float) SEGMENTS;
			double x = Mth.lerp(f, from.x, to.x);
			double y = Mth.lerp(f, from.y, to.y) - sag * (4 * f * (1 - f));
			double z = Mth.lerp(f, from.z, to.z);
			box(pose, lines, cam, new Vec3(x, y, z), LINE_BEAD, LINE_COLOR);
		}
	}

	private static void drawBobber(PoseStack pose, VertexConsumer lines, Vec3 cam, Vec3 bobber) {
		box(pose, lines, cam, bobber, BOBBER_SIZE, BOBBER_COLOR);
	}

	private static void box(PoseStack pose, VertexConsumer lines, Vec3 cam, Vec3 center, float size, int color) {
		VoxelShape shape = Shapes.box(0, 0, 0, size, size, size);
		double h = size / 2.0;
		ShapeRenderer.renderShape(pose, lines, shape,
			center.x - cam.x - h, center.y - cam.y - h, center.z - cam.z - h, color, LINE_WIDTH);
	}
}
