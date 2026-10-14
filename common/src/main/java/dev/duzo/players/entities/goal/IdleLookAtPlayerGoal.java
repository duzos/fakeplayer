package dev.duzo.players.entities.goal;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;

/** Vanilla's look-at-player, but only while the fake has nothing to do, so a working fake is never turned away from its job. */
public class IdleLookAtPlayerGoal extends LookAtPlayerGoal {
	private final FakePlayerEntity fake;

	public IdleLookAtPlayerGoal(FakePlayerEntity fake, float lookDistance) {
		super(fake, Player.class, lookDistance);
		this.fake = fake;
	}

	@Override
	public boolean canUse() {
		return this.fake.isIdleNow() && super.canUse();
	}

	@Override
	public boolean canContinueToUse() {
		return this.fake.isIdleNow() && super.canContinueToUse();
	}
}
