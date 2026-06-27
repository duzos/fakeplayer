package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Shared, side-effect-light helpers for job executors. Mirrors patterns proven in LumberjackJobExecutor. */
public final class JobHelpers {
	public static final double ARRIVE_SQR = 6.0;   // matches Courier ARRIVAL_DIST_SQR
	private JobHelpers() {}

	/** AABB for the fake's region markers, or null if unset. Inclusive of both corner blocks. */
	public static AABB regionBox(AIState s) {
		BlockPos a = s.regionA(), b = s.regionB();
		if (a == null || b == null) return null;
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		return new AABB(x0, y0, z0, x1 + 1.0, y1 + 1.0, z1 + 1.0);
	}

	/** Walk toward target; returns true once within ARRIVE_SQR (and stops navigation). */
	public static boolean walkTo(FakePlayerEntity e, BlockPos target, double speed) {
		if (e.blockPosition().distSqr(target) <= ARRIVE_SQR) { e.getNavigation().stop(); return true; }
		if (e.getNavigation().isDone())
			e.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
		return false;
	}

	public static Container containerAt(ServerLevel level, BlockPos pos) {
		return pos == null ? null : HopperBlockEntity.getContainerAt(level, pos);
	}

	/** Pull/push budget transfer using vanilla hopper logic. Returns count of stacks moved. */
	public static int transferOne(Container src, Container dst) {
		for (int i = 0; i < src.getContainerSize(); i++) {
			ItemStack stack = src.getItem(i);
			if (stack.isEmpty()) continue;
			ItemStack remainder = HopperBlockEntity.addItem(src, dst, stack.copy(), null);
			int moved = stack.getCount() - remainder.getCount();
			if (moved > 0) {
				stack.shrink(moved);
				if (stack.isEmpty()) src.setItem(i, ItemStack.EMPTY);
				src.setChanged(); dst.setChanged();
				return 1;
			}
		}
		return 0;
	}

	/** A cell the fake can occupy: no collision and no fluid (so it walks into crops/grass but not water). */
	public static boolean isPassable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	/** Feet+head passable, solid block to stand on below (and not a fluid). */
	public static boolean canStandAt(ServerLevel level, BlockPos feet) {
		return isPassable(level, feet)
				&& isPassable(level, feet.above())
				&& !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
	}

	public static boolean inventoryFull(FakePlayerEntity e) {
		SimpleContainer inv = e.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).isEmpty()) return false;
		return true;
	}

	/** Vacuum loose items within radius (mirrors Lumberjack.vacuumNearbyItems). */
	public static void vacuum(ServerLevel level, FakePlayerEntity e, double radius) {
		AABB box = e.getBoundingBox().inflate(radius);
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box,
				it -> it.isAlive() && !it.hasPickUpDelay())) {
			ItemStack stack = item.getItem().copy();
			int before = stack.getCount();
			ItemStack rem = e.getInventory().addItem(stack);
			int taken = before - rem.getCount();
			if (taken <= 0) continue;
			e.take(item, taken);
			if (rem.isEmpty()) item.discard(); else item.setItem(rem);
		}
	}
}
