package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
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

	// FOLLOW_RANGE (the pathRange config) bounds every path PathNavigation computes, so a destination beyond it
	// returns a partial (canReach() == false) path as a matter of course. Treat that as progress and walk it in
	// stages; only flag UNREACHABLE when successive completed legs stop closing the gap.
	private static final double MIN_LEG_PROGRESS = 1.0; // blocks a completed leg must close to count as progress
	private static final int STALE_LEG_LIMIT = 3;       // completed legs in a row with no progress before UNREACHABLE

	// A failed search costs 16 nodes of budget per block of pathRange, so a fake that cannot get anywhere must not
	// recompute every tick. Report failure from the cooldown instead of pathing again.
	private static final int FAIL_COOLDOWN_TICKS = 40;

	private record Progress(BlockPos dest, double lastDist, int staleLegs) {}
	private static final Map<UUID, Progress> PROGRESS = new HashMap<>();
	private static final Map<UUID, Long> RETRY_AFTER = new HashMap<>();

	/**
	 * Walk toward a walkable neighbour of target (or target itself if none is found). Returns ARRIVED once within
	 * ARRIVE_SQR horizontally and |dy| &lt;= 2 vertically of that spot, UNREACHABLE once repeated completed legs
	 * make no real progress toward it, else MOVING. Stops navigation on arrival.
	 */
	public static WalkResult walkTo(FakePlayerEntity e, BlockPos target, double speed) {
		BlockPos dest = standableNeighbor((ServerLevel) e.level(), target);
		if (atTarget(e, target)) {
			e.getNavigation().stop();
			PROGRESS.remove(e.getUUID());
			RETRY_AFTER.remove(e.getUUID());
			return WalkResult.ARRIVED;
		}
		if (!e.getNavigation().isDone()) return WalkResult.MOVING;
		return moveToChecked(e, dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, speed) ? WalkResult.MOVING : WalkResult.UNREACHABLE;
	}

	/**
	 * The arrival test {@link #walkTo} itself uses: within {@link #ARRIVE_SQR} horizontally of the standable spot
	 * it actually walks to, and no more than two blocks above or below it. Phase guards that re-check arrival must
	 * use this rather than a direct distance to target, or they disagree with walkTo and livelock against it.
	 */
	public static boolean atTarget(FakePlayerEntity e, BlockPos target) {
		BlockPos dest = standableNeighbor((ServerLevel) e.level(), target);
		BlockPos cur = e.blockPosition();
		double dx = cur.getX() - dest.getX(), dz = cur.getZ() - dest.getZ();
		return dx * dx + dz * dz <= ARRIVE_SQR && Math.abs(cur.getY() - dest.getY()) <= 2;
	}

	private static BlockPos standableNeighbor(ServerLevel level, BlockPos target) {
		for (int dy : new int[] {0, -1, 1}) {
			BlockPos base = target.above(dy);
			if (canStandAt(level, base)) return base;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos p = base.relative(d);
				if (canStandAt(level, p)) return p;
			}
		}
		return target;
	}

	/**
	 * Starts navigating toward (x, y, z) if idle. A null path is a genuine failure. A non-null path is progress
	 * even when {@code canReach()} is false - vanilla treats a partial path the same way, which is what makes
	 * multi-leg travel beyond FOLLOW_RANGE work at all - so it's always followed. Real unreachability is instead
	 * caught by tracking distance-to-destination across completed legs: once a leg finishes without closing at
	 * least {@link #MIN_LEG_PROGRESS} blocks, {@link #STALE_LEG_LIMIT} times in a row for the same destination,
	 * this reports failure instead of computing yet another going-nowhere path, and keeps reporting it without
	 * searching again until {@link #FAIL_COOLDOWN_TICKS} have passed.
	 */
	public static boolean moveToChecked(FakePlayerEntity e, double x, double y, double z, double speed) {
		BlockPos dest = BlockPos.containing(x, y, z);
		if (!e.getNavigation().isDone()) return true;

		long now = e.level().getGameTime();
		Long retryAfter = RETRY_AFTER.get(e.getUUID());
		if (retryAfter != null) {
			if (now < retryAfter) return false;
			RETRY_AFTER.remove(e.getUUID());
		}

		double dist = Math.sqrt(e.blockPosition().distSqr(dest));
		Progress prior = PROGRESS.get(e.getUUID());
		int staleLegs = 0;
		if (prior != null && prior.dest.equals(dest)) {
			staleLegs = (prior.lastDist - dist) >= MIN_LEG_PROGRESS ? 0 : prior.staleLegs + 1;
			if (staleLegs > STALE_LEG_LIMIT) { fail(e, now); return false; }
		}

		Path path = e.getNavigation().createPath(dest, 1);
		if (path == null) { fail(e, now); return false; }
		e.getNavigation().moveTo(path, speed);
		PROGRESS.put(e.getUUID(), new Progress(dest.immutable(), dist, staleLegs));
		return true;
	}

	private static void fail(FakePlayerEntity e, long now) {
		PROGRESS.remove(e.getUUID());
		RETRY_AFTER.put(e.getUUID(), now + FAIL_COOLDOWN_TICKS);
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

	/**
	 * One filter token ({@code namespace:id} or {@code #namespace:tag}) against an item drop. Tests item id, item
	 * tag, and - since some mods only tag the block, not the item - the corresponding block's id and tag too.
	 */
	public static boolean matchesFilterToken(ItemStack stack, String token) {
		boolean explicitTag = token.startsWith("#");
		String name = explicitTag ? token.substring(1).trim() : token;
		ResourceLocation id = ResourceLocation.tryParse(name);
		if (id == null) return false;
		if (!explicitTag && BuiltInRegistries.ITEM.getOptional(id).map(stack::is).orElse(false)) return true;
		if (stack.is(TagKey.create(Registries.ITEM, id))) return true;
		if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
			BlockState state = blockItem.getBlock().defaultBlockState();
			if (!explicitTag && BuiltInRegistries.BLOCK.getOptional(id).map(state::is).orElse(false)) return true;
			if (state.is(TagKey.create(Registries.BLOCK, id))) return true;
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
