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
}
