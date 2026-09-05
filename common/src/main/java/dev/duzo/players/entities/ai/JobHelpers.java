package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared, side-effect-light helpers for job executors. Mirrors patterns proven in LumberjackJobExecutor. */
public final class JobHelpers {
	public static final double ARRIVE_SQR = 6.0;   // matches Courier ARRIVAL_DIST_SQR
	private JobHelpers() {}

	private static final int CONTAINER_DWELL_TICKS = 20; // ~1s pause with the container open before the fake acts

	// Per-fake: the container it currently has visually open and the tick it may start acting on it.
	private record OpenContainer(BlockPos pos, long readyAt) {}
	private static final Map<UUID, OpenContainer> OPEN_CONTAINERS = new HashMap<>();

	/**
	 * Open the container at {@code pos} for this fake and hold it open while the fake pauses ~1s. Returns true
	 * once the pause has elapsed and the fake may act, false while it is still waiting. Closes any other
	 * container the fake had open. Call every tick the fake is servicing the container.
	 */
	public static boolean pollContainer(ServerLevel level, FakePlayerEntity e, BlockPos pos) {
		if (pos == null) { closeContainer(level, e); return false; }
		OpenContainer current = OPEN_CONTAINERS.get(e.getUUID());
		if (current == null || !current.pos.equals(pos)) {
			if (current != null) setContainerOpen(level, current.pos, false);
			setContainerOpen(level, pos, true);
			OPEN_CONTAINERS.put(e.getUUID(), new OpenContainer(pos.immutable(), level.getGameTime() + CONTAINER_DWELL_TICKS));
			return false;
		}
		return level.getGameTime() >= current.readyAt;
	}

	/** Close whatever container this fake currently has visually open. Safe to call every tick. */
	public static void closeContainer(ServerLevel level, FakePlayerEntity e) {
		OpenContainer current = OPEN_CONTAINERS.remove(e.getUUID());
		if (current != null) setContainerOpen(level, current.pos, false);
	}

	private static void setContainerOpen(ServerLevel level, BlockPos pos, boolean open) {
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof AbstractChestBlock) {
			level.blockEvent(pos, block, 1, open ? 1 : 0);  // event 1 = viewer count -> drives the lid
			playContainerSound(level, pos, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE);
		} else if (block instanceof BarrelBlock && state.hasProperty(BarrelBlock.OPEN)) {
			if (state.getValue(BarrelBlock.OPEN) != open) {
				level.setBlock(pos, state.setValue(BarrelBlock.OPEN, open), 3);
				playContainerSound(level, pos, open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE);
			}
		}
	}

	private static void playContainerSound(ServerLevel level, BlockPos pos, SoundEvent sound) {
		level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.5F, level.getRandom().nextFloat() * 0.1F + 0.9F);
	}

	/** AABB for the fake's region markers, or null if unset. Inclusive of both corner blocks. */
	public static AABB regionBox(AIState s) {
		BlockPos a = s.regionA(), b = s.regionB();
		if (a == null || b == null) return null;
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		return new AABB(x0, y0, z0, x1 + 1.0, y1 + 1.0, z1 + 1.0);
	}

	public enum WalkResult { ARRIVED, MOVING, UNREACHABLE }

	/**
	 * Walk toward a walkable neighbour of target (or target itself if none is found). Returns ARRIVED once within
	 * ARRIVE_SQR of that spot (Y included), UNREACHABLE when a freshly computed path can't reach it, else MOVING.
	 * Stops navigation on arrival.
	 */
	public static WalkResult walkTo(FakePlayerEntity e, BlockPos target, double speed) {
		BlockPos dest = standableNeighbor((ServerLevel) e.level(), target);
		if (e.blockPosition().distSqr(dest) <= ARRIVE_SQR) { e.getNavigation().stop(); return WalkResult.ARRIVED; }
		if (!e.getNavigation().isDone()) return WalkResult.MOVING;
		return moveToChecked(e, dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, speed) ? WalkResult.MOVING : WalkResult.UNREACHABLE;
	}

	private static BlockPos standableNeighbor(ServerLevel level, BlockPos target) {
		if (canStandAt(level, target)) return target;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos p = target.relative(d);
			if (canStandAt(level, p)) return p;
		}
		return target;
	}

	/**
	 * Starts navigating toward (x, y, z) if idle. Returns false - without starting to move - when a freshly
	 * computed path can't reach the destination; a "path found" that can't actually get there is not success.
	 */
	public static boolean moveToChecked(FakePlayerEntity e, double x, double y, double z, double speed) {
		if (!e.getNavigation().isDone()) return true;
		Path path = e.getNavigation().createPath(BlockPos.containing(x, y, z), 1);
		if (path == null || !path.canReach()) return false;
		e.getNavigation().moveTo(path, speed);
		return true;
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
		return isFull(e.getInventory());
	}

	/** Every slot occupied and at max count for both the stack's own cap and the container's cap. */
	public static boolean isFull(Container c) {
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty()) return false;
			if (s.getCount() < s.getMaxStackSize() && s.getCount() < c.getMaxStackSize()) return false;
		}
		return true;
	}

	/** Whether at least one unit of `stack` could be added to `c` - an empty slot, or a matching slot with headroom. */
	public static boolean canAccept(Container c, ItemStack stack) {
		if (stack.isEmpty()) return true;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty()) return true;
			if (ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < s.getMaxStackSize() && s.getCount() < c.getMaxStackSize()) return true;
		}
		return false;
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
