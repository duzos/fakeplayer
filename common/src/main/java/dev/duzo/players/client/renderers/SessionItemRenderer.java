package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.AIState;
import dev.duzo.players.entities.ai.GuardJobExecutor;
import dev.duzo.players.entities.ai.Job;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.UUID;

/**
 * Draws region/waypoint/chest-picker previews as world-space wireframe boxes.
 * <p>
 * 26.2 removed the old immediate-mode {@code MultiBufferSource}/{@code ShapeRenderer} pipeline used
 * here on 1.21.11. The replacement is the new {@code net.minecraft.gizmos} API: {@link Gizmos#cuboid}
 * submits a wireframe box to the main-thread gizmo collector, which {@code LevelExtractor} drains and
 * hands to {@code LevelRenderer} on the next frame extraction - one frame of latency, invisible at
 * 20+ fps. Binding {@code Minecraft.getInstance().levelExtractor.collectPerFrameMainThreadGizmos()}
 * around the calls is what makes {@code Gizmos.cuboid(...)} (a bare thread-local push) actually reach
 * that collector instead of a no-op default.
 */
public final class SessionItemRenderer {
	private static final int COLOR_PENDING = 0xFFFFD933;
	private static final int COLOR_LIVE = 0xFF66E5FF;
	private static final int COLOR_COMMITTED = 0x80AAFFAA;
	private static final int COLOR_WAYPOINT_LIVE = 0xFF55EAFF;
	private static final int COLOR_WAYPOINT_FAINT = 0x5555EAFF;
	private static final int COLOR_PATROL_ADD = 0xFF54E08C;
	private static final int COLOR_PATROL_REMOVE = 0xFFE76060;
	private static final int COLOR_CHEST_OK = 0xFF54E08C;
	private static final int COLOR_CHEST_BAD = 0xFFE76060;
	private static final int COLOR_CHEST_FAINT = 0x5554E08C;
	private static final float LINE_WIDTH = 2.5F;

	private SessionItemRenderer() {}

	// The PoseStack argument is unused (Gizmos are submitted in world space, not camera-relative
	// local space) but kept so both loaders' world-render hooks can call this unchanged.
	public static void render(PoseStack pose) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) return;

		ItemStack stack = sessionStack(player.getMainHandItem(), player.getOffhandItem());
		if (stack == null) return;
		byte purpose = AIMarkerItem.purpose(stack);

		FakePlayerEntity bound = findFake(level, AIMarkerItem.fakeUUID(stack));
		BlockPos crosshair = crosshairBlock(mc);

		try (var ignored = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
			switch (purpose) {
				case AIMarkerItem.PURPOSE_REGION -> renderRegion(stack, bound, crosshair);
				case AIMarkerItem.PURPOSE_WAYPOINT -> renderWaypoint(bound, crosshair);
				case AIMarkerItem.PURPOSE_CHEST_PICKER -> renderChestPicker(level, bound, crosshair);
				default -> {}
			}
		}
	}

	private static void renderRegion(ItemStack stack, FakePlayerEntity bound, BlockPos crosshair) {
		BlockPos committedA = null, committedB = null;
		if (bound != null) {
			AIState ai = bound.getAIState();
			committedA = ai.regionA();
			committedB = ai.regionB();
		}

		BlockPos stackA = AIMarkerItem.regionA(stack);
		if (stackA == null) {
			if (crosshair != null) drawBlock(crosshair, COLOR_LIVE);
			drawCommittedRegion(committedA, committedB);
		} else {
			drawBlock(stackA, COLOR_PENDING);
			if (crosshair != null) drawBox(stackA, crosshair, COLOR_LIVE);
			drawCommittedRegion(committedA, committedB);
		}
	}

	private static void renderWaypoint(FakePlayerEntity bound, BlockPos crosshair) {
		if (bound != null && bound.getAIState().job() == Job.GUARD) {
			renderPatrolEditor(bound, crosshair);
			return;
		}
		BlockPos committed = bound == null ? null : bound.getAIState().waypoint();
		if (committed != null) drawBlock(committed, COLOR_WAYPOINT_FAINT);
		if (crosshair != null) drawBlock(crosshair, COLOR_WAYPOINT_LIVE);
	}

	// While holding the marker on a guard, show every patrol point. The block under the
	// crosshair turns red if it's an existing point (sneak + right-click removes it) or
	// green if it's empty (right-click adds it).
	private static void renderPatrolEditor(FakePlayerEntity bound, BlockPos crosshair) {
		boolean crosshairIsPoint = false;
		for (long packed : GuardJobExecutor.readPatrolPoints(bound.getAIState())) {
			BlockPos p = BlockPos.of(packed);
			boolean hovered = p.equals(crosshair);
			if (hovered) crosshairIsPoint = true;
			drawBlock(p, hovered ? COLOR_PATROL_REMOVE : COLOR_WAYPOINT_LIVE);
		}
		if (crosshair != null && !crosshairIsPoint) drawBlock(crosshair, COLOR_PATROL_ADD);
	}

	private static void renderChestPicker(ClientLevel level, FakePlayerEntity bound, BlockPos crosshair) {
		BlockPos committed = bound == null ? null : bound.getAIState().depositChest();
		if (committed != null) drawBlock(committed, COLOR_CHEST_FAINT);
		if (crosshair != null) {
			int color = AIMarkerItem.isValidContainer(level, crosshair) ? COLOR_CHEST_OK : COLOR_CHEST_BAD;
			drawBlock(crosshair, color);
		}
	}

	private static void drawCommittedRegion(BlockPos a, BlockPos b) {
		if (a == null || b == null) return;
		drawBox(a, b, COLOR_COMMITTED);
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

	private static void drawBlock(BlockPos pos, int color) {
		Gizmos.cuboid(new AABB(pos), GizmoStyle.stroke(color, LINE_WIDTH));
	}

	private static void drawBox(BlockPos a, BlockPos b, int color) {
		Gizmos.cuboid(AABB.encapsulatingFullBlocks(a, b), GizmoStyle.stroke(color, LINE_WIDTH));
	}
}
