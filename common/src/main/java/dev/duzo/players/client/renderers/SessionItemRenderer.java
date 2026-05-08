package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.AIState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.UUID;

public final class SessionItemRenderer {
	private static final int COLOR_PENDING = 0xFFFFD933;
	private static final int COLOR_LIVE = 0xFF66E5FF;
	private static final int COLOR_COMMITTED = 0x80AAFFAA;

	private SessionItemRenderer() {}

	public static void render(PoseStack pose) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) return;

		ItemStack stack = sessionStack(player.getMainHandItem(), player.getOffhandItem());
		if (stack == null) return;
		if (AIMarkerItem.purpose(stack) != AIMarkerItem.PURPOSE_REGION) return;

		MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		VertexConsumer lines = buffers.getBuffer(RenderType.lines());
		Vec3 cam = mc.gameRenderer.getMainCamera().position();

		FakePlayerEntity bound = findFake(level, AIMarkerItem.fakeUUID(stack));
		BlockPos committedA = null, committedB = null;
		if (bound != null) {
			AIState ai = bound.getAIState();
			committedA = ai.regionA();
			committedB = ai.regionB();
		}

		BlockPos stackA = AIMarkerItem.regionA(stack);
		BlockPos crosshair = crosshairBlock(mc);

		if (stackA == null) {
			if (crosshair != null) drawBlock(pose, lines, cam, crosshair, COLOR_LIVE);
			drawCommitted(pose, lines, cam, committedA, committedB);
		} else {
			drawBlock(pose, lines, cam, stackA, COLOR_PENDING);
			if (crosshair != null) drawBox(pose, lines, cam, stackA, crosshair, COLOR_LIVE);
			drawCommitted(pose, lines, cam, committedA, committedB);
		}

		buffers.endBatch(RenderType.lines());
	}

	private static void drawCommitted(PoseStack pose, VertexConsumer lines, Vec3 cam, BlockPos a, BlockPos b) {
		if (a == null || b == null) return;
		drawBox(pose, lines, cam, a, b, COLOR_COMMITTED);
	}

	private static ItemStack sessionStack(ItemStack main, ItemStack off) {
		if (AIMarkerItem.isSession(main)) return main;
		if (AIMarkerItem.isSession(off)) return off;
		return null;
	}

	private static FakePlayerEntity findFake(ClientLevel level, UUID id) {
		if (id == null) return null;
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof FakePlayerEntity f && id.equals(f.getUUID())) return f;
		}
		return null;
	}

	private static BlockPos crosshairBlock(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
			return bhr.getBlockPos();
		}
		return null;
	}

	private static void drawBlock(PoseStack pose, VertexConsumer lines, Vec3 cam, BlockPos pos, int color) {
		VoxelShape shape = Shapes.box(0, 0, 0, 1, 1, 1);
		ShapeRenderer.renderShape(pose, lines, shape, pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z, color);
	}

	private static void drawBox(PoseStack pose, VertexConsumer lines, Vec3 cam, BlockPos a, BlockPos b, int color) {
		double minX = Math.min(a.getX(), b.getX());
		double minY = Math.min(a.getY(), b.getY());
		double minZ = Math.min(a.getZ(), b.getZ());
		double maxX = Math.max(a.getX(), b.getX()) + 1.0;
		double maxY = Math.max(a.getY(), b.getY()) + 1.0;
		double maxZ = Math.max(a.getZ(), b.getZ()) + 1.0;
		VoxelShape shape = Shapes.box(0, 0, 0, maxX - minX, maxY - minY, maxZ - minZ);
		ShapeRenderer.renderShape(pose, lines, shape, minX - cam.x, minY - cam.y, minZ - cam.z, color);
	}
}
