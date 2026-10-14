package dev.duzo.players.entities.goal;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/** Vanilla melee that stands down while the fake is seated. Named, not anonymous: see JobAwareTemptGoal. */
public class FakeMeleeAttackGoal extends MeleeAttackGoal {
	private final FakePlayerEntity fake;

	public FakeMeleeAttackGoal(FakePlayerEntity fake, double speed, boolean followEvenIfNotSeen) {
		super(fake, speed, followEvenIfNotSeen);
		this.fake = fake;
	}

	@Override
	public boolean canUse() {
		return !this.fake.isRiding() && super.canUse();
	}

	@Override
	public boolean canContinueToUse() {
		return !this.fake.isRiding() && super.canContinueToUse();
	}
}
