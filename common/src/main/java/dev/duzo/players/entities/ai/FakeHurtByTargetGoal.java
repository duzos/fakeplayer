package dev.duzo.players.entities.ai;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

/**
 * Retaliation that gives up at vanilla's 16 blocks rather than at FOLLOW_RANGE, which the pathRange config
 * raises well past it. Named rather than anonymous: regular Forge on 1.20.1 rejects anonymous subclasses of
 * remapped Minecraft types.
 */
public class FakeHurtByTargetGoal extends HurtByTargetGoal {
	private static final double CHASE_RANGE = 16.0;

	public FakeHurtByTargetGoal(PathfinderMob mob) {
		super(mob);
	}

	@Override
	protected double getFollowDistance() {
		return CHASE_RANGE;
	}

	// a fake never damages these (see FriendlyFire), so locking on would only burn ammo on a target it cannot hurt
	@Override
	public boolean canUse() {
		return !FriendlyFire.shouldBlock(this.mob, this.mob.getLastHurtByMob()) && super.canUse();
	}

	@Override
	public boolean canContinueToUse() {
		return !FriendlyFire.shouldBlock(this.mob, this.mob.getTarget()) && super.canContinueToUse();
	}
}
