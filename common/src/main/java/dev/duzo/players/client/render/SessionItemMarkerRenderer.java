package dev.duzo.players.client.render;

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
 * Client-side world renderer for session item markers. Draws debug-line block
 * outlines for the crosshair-target while a session marker is in hand, plus a
 * faint outline of the value already committed on the bound fake.
 *
 * Designed to be extended: each {@link Purpose} owns its tinting and value
 * resolution. Add a new entry to expose another marker purpose to the renderer
 * (REGION lives next to WAYPOINT and CHEST_PICKER once #30 lands).
 */
public final class SessionItemMarkerRenderer {
	private static final double PICK_DISTANCE = 32.0D;

	private static final float[] WAYPOINT_LIVE = rgba(0x55, 0xEA, 0xFF, 0xFF);
	private static final float[] WAYPOINT_FAINT = rgba(0x55, 0xEA, 0xFF, 0x55);
	private static final float[] CHEST_LIVE_OK = rgba(0x54, 0xE0, 0x8C, 0xFF);
	private static final float[] CHEST_LIVE_BAD = rgba(0xE7, 0x60, 0x60, 0xFF);
	private static final float[] CHEST_FAINT = rgba(0x54, 0xE0, 0x8C, 0x55);

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
			BlockPos committed = m.purpose.committed(m.fake);
			if (committed != null) {
				drawOutline(poseStack, lines, committed, cam, m.purpose.faintColor());
			}
			if (crosshair != null) {
				float[] color = m.purpose.crosshairColor(mc, crosshair);
				drawOutline(poseStack, lines, crosshair, cam, color);
			}
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
			out.add(new ActiveMarker(purpose, fake));
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
		ShapeRenderer.renderLineBox(pose, lines,
				box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
				rgba[0], rgba[1], rgba[2], rgba[3]);
	}

	private static float[] rgba(int r, int g, int b, int a) {
		return new float[]{r / 255.0F, g / 255.0F, b / 255.0F, a / 255.0F};
	}

	private record ActiveMarker(Purpose purpose, FakePlayerEntity fake) {}

	/**
	 * Per-purpose hook so additional session item types (e.g. REGION from #30)
	 * can plug in by adding an enum entry without touching the renderer body.
	 */
	private enum Purpose {
		WAYPOINT {
			@Override @Nullable BlockPos committed(FakePlayerEntity fake) {
				AIState s = fake.getAIState();
				return s == null ? null : s.waypoint();
			}
			@Override float[] faintColor() { return WAYPOINT_FAINT; }
			@Override float[] crosshairColor(Minecraft mc, BlockPos pos) { return WAYPOINT_LIVE; }
		},
		CHEST_PICKER {
			@Override @Nullable BlockPos committed(FakePlayerEntity fake) {
				AIState s = fake.getAIState();
				return s == null ? null : s.depositChest();
			}
			@Override float[] faintColor() { return CHEST_FAINT; }
			@Override float[] crosshairColor(Minecraft mc, BlockPos pos) {
				if (mc.level == null) return CHEST_LIVE_BAD;
				return AIMarkerItem.isValidContainer(mc.level, pos) ? CHEST_LIVE_OK : CHEST_LIVE_BAD;
			}
		};

		abstract @Nullable BlockPos committed(FakePlayerEntity fake);
		abstract float[] faintColor();
		abstract float[] crosshairColor(Minecraft mc, BlockPos pos);

		@Nullable static Purpose fromStack(ItemStack stack) {
			byte raw = AIMarkerItem.purposeOf(stack);
			return switch (raw) {
				case AIMarkerItem.PURPOSE_WAYPOINT -> WAYPOINT;
				case AIMarkerItem.PURPOSE_CHEST_PICKER -> CHEST_PICKER;
				default -> null;
			};
		}
	}
}
