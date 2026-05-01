package dev.duzo.players.entities.goal;

import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;
import java.util.List;

public class MoveTowardsItemsGoal extends Goal {
	protected final PathfinderMob mob;
	private final double speedModifier;
	private final boolean followingTargetEvenIfNotSeen;

	public MoveTowardsItemsGoal(PathfinderMob mob, double speedModifier, boolean followingTargetEvenIfNotSeen) {
		this.mob = mob;
		this.speedModifier = speedModifier;
		this.followingTargetEvenIfNotSeen = followingTargetEvenIfNotSeen;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	public static List<ItemEntity> getNearbyItems(PathfinderMob entity, double radius) {
		List<ItemEntity> list = entity.level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(radius));
		return list;
	}

	@Override
	public boolean canUse() {
		return !getNearbyItems(this.mob, 4).isEmpty();
	}

	public boolean canContinueToUse() {
		LivingEntity livingentity = this.mob.getTarget();
		if (livingentity == null || !livingentity.isAlive() || !this.mob.isWithinHome(livingentity.blockPosition())) {
			return false;
		}
		if (!this.followingTargetEvenIfNotSeen) {
			return !this.mob.getNavigation().isDone();
		} else {
			return !(livingentity instanceof Player) || !livingentity.isSpectator() && !((Player) livingentity).isCreative();
		}
	}

	public void start() {
		List<ItemEntity> nearbyItems = getNearbyItems(this.mob, 4);
		assert !nearbyItems.isEmpty();
		for (ItemEntity checkedItem : nearbyItems) { // @TODO: If this has lag, maybe add a maximum depth we can go
			if (checkedItem.onGround()) {
				this.mob.getNavigation().moveTo(checkedItem.getX(), checkedItem.getY(), checkedItem.getZ(), this.speedModifier);
				break;
			}
		}

	}

	public void stop() {
		LivingEntity livingentity = this.mob.getTarget();
		if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(livingentity)) {
			this.mob.setTarget((LivingEntity) null);
		}
		this.mob.getNavigation().stop();
	}
}