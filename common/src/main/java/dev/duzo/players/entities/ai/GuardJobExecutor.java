package dev.duzo.players.entities.ai;

import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class GuardJobExecutor implements JobExecutor {
	private static final double SPEED = 1.0;
	private static final double ARRIVAL_DIST_SQR = 4.0;
	private static final int WAIT_TICKS = 40;
	private static final int RESCAN_INTERVAL = 10;
	private static final int PATROL_REPATH_INTERVAL = 20;

	private static final String TAG_PATROL = "Patrol";
	private static final String TAG_RADIUS = "Radius";
	private static final String TAG_INDEX = "Index";
	private static final String TAG_WAIT = "Wait";

	private int idx = 0;
	private int waitCounter = 0;
	private int scanCooldown = 0;
	private int patrolRepathCooldown = 0;

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		long[] points = readPatrolPoints(entity.getAIState());
		if (points.length < 2) {
			entity.getNavigation().stop();
			return;
		}
		if (idx >= points.length) idx = 0;

		BlockPos center = BlockPos.of(points[idx]);
		int radius = readRadius(entity.getAIState());

		LivingEntity target = entity.getTarget();
		BlockPos guardPos = entity.blockPosition();
		if (target == null || !target.isAlive() || outOfRadius(target, guardPos, radius)) {
			if (scanCooldown <= 0) {
				LivingEntity nearest = findHostile(level, entity, guardPos, radius);
				if (nearest != null) entity.setTarget(nearest);
				else if (target != null) {
					entity.setTarget(null);
					entity.getNavigation().stop();
				}
				scanCooldown = RESCAN_INTERVAL;
			} else {
				scanCooldown--;
			}
		} else {
			scanCooldown = RESCAN_INTERVAL;
		}

		LivingEntity active = entity.getTarget();
		if (active != null && active.isAlive() && !outOfRadius(active, entity.blockPosition(), radius)) {
			return;
		}

		if (entity.blockPosition().distSqr(center) <= ARRIVAL_DIST_SQR) {
			entity.getNavigation().stop();
			patrolRepathCooldown = 0;
			if (waitCounter < WAIT_TICKS) {
				waitCounter++;
				return;
			}
			waitCounter = 0;
			idx = (idx + 1) % points.length;
			return;
		}

		if (patrolRepathCooldown > 0) patrolRepathCooldown--;
		if (patrolRepathCooldown <= 0 || entity.getNavigation().isDone()) {
			entity.getNavigation().moveTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, SPEED);
			patrolRepathCooldown = PATROL_REPATH_INTERVAL;
		}
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
		entity.setTarget(null);
	}

	@Override
	public void onResume(FakePlayerEntity entity) {}

	@Override
	public CompoundTag serialize() {
		CompoundTag t = new CompoundTag();
		t.putInt(TAG_INDEX, idx);
		t.putInt(TAG_WAIT, waitCounter);
		return t;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null) return;
		idx = tag.getIntOr(TAG_INDEX, 0);
		waitCounter = tag.getIntOr(TAG_WAIT, 0);
	}

	private static boolean outOfRadius(LivingEntity mob, BlockPos center, int r) {
		double cx = center.getX() + 0.5;
		double cy = center.getY() + 0.5;
		double cz = center.getZ() + 0.5;
		double dx = mob.getX() - cx;
		double dy = mob.getY() - cy;
		double dz = mob.getZ() - cz;
		return dx * dx + dy * dy + dz * dz > (double) r * r;
	}

	private static LivingEntity findHostile(ServerLevel level, FakePlayerEntity entity, BlockPos center, int r) {
		AABB box = new AABB(center).inflate(r);
		List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box, m -> m instanceof Enemy && m.isAlive() && entity.hasLineOfSight(m));
		LivingEntity nearest = null;
		double best = Double.MAX_VALUE;
		for (Mob m : mobs) {
			double d = m.distanceToSqr(entity);
			if (d < best) {
				best = d;
				nearest = m;
			}
		}
		return nearest;
	}

	public static long[] readPatrolPoints(AIState state) {
		return state.jobParams().getLongArray(TAG_PATROL).orElse(new long[0]);
	}

	public static int readRadius(AIState state) {
		return state.jobParams().getIntOr(TAG_RADIUS, PlayersConfig.get().guardRadius);
	}

	public static void appendPatrolPoint(AIState state, BlockPos pos) {
		long[] cur = readPatrolPoints(state);
		long[] next = new long[cur.length + 1];
		System.arraycopy(cur, 0, next, 0, cur.length);
		next[cur.length] = pos.asLong();
		CompoundTag params = state.jobParams();
		params.putLongArray(TAG_PATROL, next);
		state.setJobParams(params);
	}

	// Removes every patrol point at the given block; returns true if any were removed.
	public static boolean removePatrolPoint(AIState state, BlockPos pos) {
		long[] cur = readPatrolPoints(state);
		long key = pos.asLong();
		long[] next = java.util.Arrays.stream(cur).filter(l -> l != key).toArray();
		if (next.length == cur.length) return false;
		CompoundTag params = state.jobParams();
		if (next.length == 0) params.remove(TAG_PATROL);
		else params.putLongArray(TAG_PATROL, next);
		state.setJobParams(params);
		return true;
	}

	public static void clearPatrol(AIState state) {
		CompoundTag params = state.jobParams();
		params.remove(TAG_PATROL);
		state.setJobParams(params);
		state.setJobState(new CompoundTag());
	}
}
