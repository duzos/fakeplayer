package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public class LumberjackJobExecutor implements JobExecutor {
	private static final double ARRIVAL_DIST_SQR = 9.0;
	private static final double SPEED = 1.0;
	private static final int BREAK_TICKS = 30;
	private static final int SWING_EVERY = 5;
	private static final int FELL_CAP = 64;
	private static final int PATH_FAILS_BEFORE_SKIP = 3;

	private enum State { SCANNING, PATHING, BREAKING }

	private State state = State.SCANNING;
	private BlockPos target;
	private int breakProgress;
	private int pathFails;
	private final Deque<BlockPos> fellQueue = new ArrayDeque<>();
	private final Set<BlockPos> fellVisited = new HashSet<>();

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		BlockPos a = entity.getAIState().regionA();
		BlockPos b = entity.getAIState().regionB();
		if (a == null || b == null) return;

		switch (state) {
			case SCANNING -> doScan(level, entity, a, b);
			case PATHING -> doPath(level, entity);
			case BREAKING -> doBreak(level, entity);
		}
	}

	private void doScan(ServerLevel level, FakePlayerEntity entity, BlockPos a, BlockPos b) {
		while (!fellQueue.isEmpty()) {
			BlockPos next = fellQueue.poll();
			if (level.getBlockState(next).is(BlockTags.LOGS)) {
				target = next;
				pathFails = 0;
				state = State.PATHING;
				return;
			}
		}
		fellVisited.clear();
		BlockPos closest = findNearestLog(level, entity, a, b);
		if (closest == null) return;
		target = closest;
		pathFails = 0;
		state = State.PATHING;
	}

	private BlockPos findNearestLog(ServerLevel level, FakePlayerEntity entity, BlockPos a, BlockPos b) {
		int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
		int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
		int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
		BlockPos selfPos = entity.blockPosition();
		BlockPos best = null;
		double bestDistSqr = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = minY; y <= maxY; y++) {
					cursor.set(x, y, z);
					if (level.getBlockState(cursor).is(BlockTags.LOGS)) {
						double d = cursor.distSqr(selfPos);
						if (d < bestDistSqr) {
							bestDistSqr = d;
							best = cursor.immutable();
						}
					}
				}
			}
		}
		return best;
	}

	private void doPath(ServerLevel level, FakePlayerEntity entity) {
		if (target == null || !level.getBlockState(target).is(BlockTags.LOGS)) {
			state = State.SCANNING;
			return;
		}
		if (entity.blockPosition().distSqr(target) <= ARRIVAL_DIST_SQR) {
			entity.getNavigation().stop();
			entity.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
			state = State.BREAKING;
			breakProgress = 0;
			return;
		}
		if (entity.getNavigation().isDone()) {
			boolean ok = entity.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, SPEED);
			if (!ok) {
				pathFails++;
				if (pathFails >= PATH_FAILS_BEFORE_SKIP) {
					target = null;
					state = State.SCANNING;
				}
			}
		}
	}

	private void doBreak(ServerLevel level, FakePlayerEntity entity) {
		if (target == null || !level.getBlockState(target).is(BlockTags.LOGS)) {
			state = State.SCANNING;
			return;
		}
		if (entity.blockPosition().distSqr(target) > ARRIVAL_DIST_SQR + 4.0) {
			state = State.PATHING;
			return;
		}
		entity.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
		if (breakProgress % SWING_EVERY == 0) {
			entity.swing(InteractionHand.MAIN_HAND);
		}
		breakProgress++;
		if (breakProgress >= BREAK_TICKS) {
			BlockPos broken = target;
			level.destroyBlock(broken, true, entity);
			enqueueConnectedAbove(level, broken);
			target = null;
			state = State.SCANNING;
		}
	}

	private void enqueueConnectedAbove(ServerLevel level, BlockPos broken) {
		Deque<BlockPos> bfs = new ArrayDeque<>();
		fellVisited.add(broken);
		bfs.add(broken);
		int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
		while (!bfs.isEmpty() && fellVisited.size() < FELL_CAP) {
			BlockPos cur = bfs.poll();
			for (int[] off : offsets) {
				if (fellVisited.size() >= FELL_CAP) break;
				BlockPos n = cur.offset(off[0], off[1], off[2]);
				if (n.getY() < broken.getY()) continue;
				if (!fellVisited.add(n)) continue;
				if (level.getBlockState(n).is(BlockTags.LOGS)) {
					fellQueue.offer(n);
					bfs.offer(n);
				}
			}
		}
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
	}

	@Override public void onResume(FakePlayerEntity entity) {}
	@Override public CompoundTag serialize() { return new CompoundTag(); }
	@Override public void deserialize(CompoundTag tag) {}
}
