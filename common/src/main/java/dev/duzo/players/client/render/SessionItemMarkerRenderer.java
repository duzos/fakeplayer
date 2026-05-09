package dev.duzo.players.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.AIState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client-side world renderer for session item markers. Each {@link Purpose}
 * owns its own draw logic; the top-level loop just iterates active markers.
 */
public final class SessionItemMarkerRenderer {
	private static final double PICK_DISTANCE = 32.0D;

	private static final float[] WAYPOINT_LIVE = rgba(0x55, 0xEA, 0xFF, 0xFF);
	private static final float[] WAYPOINT_FAINT = rgba(0x55, 0xEA, 0xFF, 0x55);
	private static final float[] CHEST_LIVE_OK = rgba(0x54, 0xE0, 0x8C, 0xFF);
	private static final float[] CHEST_LIVE_BAD = rgba(0xE7, 0x60, 0x60, 0xFF);
	private static final float[] CHEST_FAINT = rgba(0x54, 0xE0, 0x8C, 0x55);
	private static final float[] REGION_LIVE = rgba(0x66, 0xE5, 0xFF, 0xFF);
	private static final float[] REGION_PENDING = rgba(0xFF, 0xD9, 0x33, 0xFF);
	private static final float[] REGION_FAINT = rgba(0xAA, 0xFF, 0xAA, 0x80);

	private SessionItemMarkerRenderer() {}

	public static void render(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 cam) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) return;

		List<ActiveMarker> active = collectActiveMarkers(player, mc.level);
		if (active.isEmpty()) return;

		BlockPos crosshair = pickBlock(player);
		VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());

		for (ActiveMarker m : active) {
			m.purpose.render(poseStack, lines, cam, mc, m.stack, m.fake, crosshair);
		}
	}

	@Nullable
	private static BlockPos pickBlock(LocalPlayer player) {
		Vec3 eye = player.getEyePosition(1.0F);
		Vec3 look = player.getViewVector(1.0F);
		Vec3 end = eye.add(look.x * PICK_DISTANCE, look.y * PICK_DISTANCE, look.z * PICK_DISTANCE);
		HitResult hit = player.level().clip(new ClipContext(eye, end,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK) return null;
		return ((BlockHitResult) hit).getBlockPos();
	}

	private static List<ActiveMarker> collectActiveMarkers(LocalPlayer player, ClientLevel level) {
		List<ActiveMarker> out = new ArrayList<>(2);
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!AIMarkerItem.isSession(stack)) continue;
			Purpose purpose = Purpose.fromStack(stack);
			if (purpose == null) continue;
			UUID fakeId = AIMarkerItem.fakeUUID(stack);
			if (fakeId == null) continue;
			Entity raw = findEntity(level, fakeId);
			if (!(raw instanceof FakePlayerEntity fake)) continue;
			out.add(new ActiveMarker(purpose, stack, fake));
		}
		return out;
	}

	@Nullable
	private static Entity findEntity(ClientLevel level, UUID id) {
		for (Entity e : level.entitiesForRendering()) {
			if (id.equals(e.getUUID())) return e;
		}
		return null;
	}

	private static void drawOutline(PoseStack pose, VertexConsumer lines, BlockPos pos, Vec3 cam, float[] rgba) {
		AABB box = new AABB(pos).move(-cam.x, -cam.y, -cam.z);
		LevelRenderer.renderLineBox(pose, lines,
				box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
				rgba[0], rgba[1], rgba[2], rgba[3]);
	}

	private static void drawBox(PoseStack pose, VertexConsumer lines, BlockPos a, BlockPos b, Vec3 cam, float[] rgba) {
		double minX = Math.min(a.getX(), b.getX()) - cam.x;
		double minY = Math.min(a.getY(), b.getY()) - cam.y;
		double minZ = Math.min(a.getZ(), b.getZ()) - cam.z;
		double maxX = Math.max(a.getX(), b.getX()) + 1.0 - cam.x;
		double maxY = Math.max(a.getY(), b.getY()) + 1.0 - cam.y;
		double maxZ = Math.max(a.getZ(), b.getZ()) + 1.0 - cam.z;
		LevelRenderer.renderLineBox(pose, lines, minX, minY, minZ, maxX, maxY, maxZ,
				rgba[0], rgba[1], rgba[2], rgba[3]);
	}

	private static float[] rgba(int r, int g, int b, int a) {
		return new float[]{r / 255.0F, g / 255.0F, b / 255.0F, a / 255.0F};
	}

	private record ActiveMarker(Purpose purpose, ItemStack stack, FakePlayerEntity fake) {}

	private enum Purpose {
		WAYPOINT {
			@Override void render(PoseStack pose, VertexConsumer lines, Vec3 cam, Minecraft mc,
			                     ItemStack stack, FakePlayerEntity fake, @Nullable BlockPos crosshair) {
				AIState s = fake.getAIState();
				BlockPos committed = s == null ? null : s.waypoint();
				if (committed != null) drawOutline(pose, lines, committed, cam, WAYPOINT_FAINT);
				if (crosshair != null) drawOutline(pose, lines, crosshair, cam, WAYPOINT_LIVE);
			}
		},
		CHEST_PICKER {
			@Override void render(PoseStack pose, VertexConsumer lines, Vec3 cam, Minecraft mc,
			                     ItemStack stack, FakePlayerEntity fake, @Nullable BlockPos crosshair) {
				AIState s = fake.getAIState();
				BlockPos committed = s == null ? null : s.depositChest();
				if (committed != null) drawOutline(pose, lines, committed, cam, CHEST_FAINT);
				if (crosshair != null) {
					float[] color = mc.level != null && AIMarkerItem.isValidContainer(mc.level, crosshair)
							? CHEST_LIVE_OK : CHEST_LIVE_BAD;
					drawOutline(pose, lines, crosshair, cam, color);
				}
			}
		},
		REGION {
			@Override void render(PoseStack pose, VertexConsumer lines, Vec3 cam, Minecraft mc,
			                     ItemStack stack, FakePlayerEntity fake, @Nullable BlockPos crosshair) {
				AIState s = fake.getAIState();
				BlockPos committedA = s == null ? null : s.regionA();
				BlockPos committedB = s == null ? null : s.regionB();
				if (committedA != null && committedB != null) {
					drawBox(pose, lines, committedA, committedB, cam, REGION_FAINT);
				}
				BlockPos stackA = AIMarkerItem.regionA(stack);
				if (stackA == null) {
					if (crosshair != null) drawOutline(pose, lines, crosshair, cam, REGION_LIVE);
				} else {
					drawOutline(pose, lines, stackA, cam, REGION_PENDING);
					if (crosshair != null) drawBox(pose, lines, stackA, crosshair, cam, REGION_LIVE);
				}
			}
		};

		abstract void render(PoseStack pose, VertexConsumer lines, Vec3 cam, Minecraft mc,
		                     ItemStack stack, FakePlayerEntity fake, @Nullable BlockPos crosshair);

		@Nullable static Purpose fromStack(ItemStack stack) {
			byte raw = AIMarkerItem.purposeOf(stack);
			return switch (raw) {
				case AIMarkerItem.PURPOSE_WAYPOINT -> WAYPOINT;
				case AIMarkerItem.PURPOSE_CHEST_PICKER -> CHEST_PICKER;
				case AIMarkerItem.PURPOSE_REGION -> REGION;
				default -> null;
			};
		}
	}
}
