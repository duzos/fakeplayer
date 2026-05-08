package dev.duzo.players.entities.goal;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.AIState;
import dev.duzo.players.entities.ai.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.EnumSet;
import java.util.UUID;

public class FollowOwnerGoal extends Goal {
	private static final double FOLLOW_RANGE = 32.0D;
	private static final double FOLLOW_RANGE_SQ = FOLLOW_RANGE * FOLLOW_RANGE;
	private static final double STOP_DISTANCE_SQ = 4.0D * 4.0D;
	private static final double START_DISTANCE_SQ = 6.0D * 6.0D;
	private static final double TELEPORT_DISTANCE_SQ = 16.0D * 16.0D;
	private static final double SPEED = 1.0D;

	private final FakePlayerEntity entity;
	private Player owner;
	private int recheckCooldown;
	private float oldWaterCost;

	public FollowOwnerGoal(FakePlayerEntity entity) {
		this.entity = entity;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		Player found = findOwner();
		if (found == null) return false;
		this.owner = found;
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		if (!isActiveByState()) return false;
		if (this.owner == null || !this.owner.isAlive()) return false;
		if (this.owner.level() != this.entity.level()) return false;
		return this.entity.distanceToSqr(this.owner) <= FOLLOW_RANGE_SQ;
	}

	@Override
	public void start() {
		this.recheckCooldown = 0;
		this.oldWaterCost = this.entity.getPathfindingMalus(BlockPathTypes.WATER);
		this.entity.setPathfindingMalus(BlockPathTypes.WATER, 0.0F);
	}

	@Override
	public void stop() {
		this.owner = null;
		this.entity.getNavigation().stop();
		this.entity.setPathfindingMalus(BlockPathTypes.WATER, this.oldWaterCost);
	}

	@Override
	public void tick() {
		if (this.owner == null) return;
		this.entity.getLookControl().setLookAt(this.owner, 10.0F, this.entity.getMaxHeadXRot());
		if (--this.recheckCooldown > 0) return;
		this.recheckCooldown = 10;

		double distSq = this.entity.distanceToSqr(this.owner);
		if (distSq <= STOP_DISTANCE_SQ) {
			this.entity.getNavigation().stop();
			return;
		}

		if (distSq >= TELEPORT_DISTANCE_SQ) {
			tryTeleport();
			return;
		}

		if (distSq >= START_DISTANCE_SQ || this.entity.getNavigation().isDone()) {
			this.entity.getNavigation().moveTo(this.owner, SPEED);
		}
	}

	private void tryTeleport() {
		BlockPos target = this.owner.blockPosition();
		for (int i = 0; i < 10; i++) {
			int x = target.getX() + randomIntInclusive(-3, 3);
			int y = target.getY() + randomIntInclusive(-1, 1);
			int z = target.getZ() + randomIntInclusive(-3, 3);
			if (tryTeleportTo(x, y, z)) return;
		}
	}

	private boolean tryTeleportTo(int x, int y, int z) {
		if (Math.abs((double) x - this.owner.getX()) < 2.0D && Math.abs((double) z - this.owner.getZ()) < 2.0D) {
			return false;
		}
		if (!isTeleportFriendlyBlock(new BlockPos(x, y, z))) return false;
		this.entity.moveTo((double) x + 0.5D, (double) y, (double) z + 0.5D, this.entity.getYRot(), this.entity.getXRot());
		this.entity.getNavigation().stop();
		return true;
	}

	private boolean isTeleportFriendlyBlock(BlockPos pos) {
		BlockPathTypes type = WalkNodeEvaluator.getBlockPathTypeStatic(this.entity.level(), pos.mutable());
		if (type != BlockPathTypes.WALKABLE) return false;
		BlockPos delta = pos.subtract(this.entity.blockPosition());
		return this.entity.level().noCollision(this.entity, this.entity.getBoundingBox().move(delta));
	}

	private int randomIntInclusive(int min, int max) {
		return this.entity.getRandom().nextInt(max - min + 1) + min;
	}

	private boolean isActiveByState() {
		AIState state = this.entity.getAIState();
		return state.job() == Job.FOLLOW && state.ownerUUID() != null;
	}

	private Player findOwner() {
		if (!isActiveByState()) return null;
		UUID ownerId = this.entity.getAIState().ownerUUID();
		Player p = this.entity.level().getPlayerByUUID(ownerId);
		if (p == null || !p.isAlive()) return null;
		if (p.level() != this.entity.level()) return null;
		if (this.entity.distanceToSqr(p) > FOLLOW_RANGE_SQ) return null;
		return p;
	}
}
