package dev.duzo.players.entities.goal;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;

public class HumanoidWaterAvoidingRandomStrollGoal extends WaterAvoidingRandomStrollGoal {
	public HumanoidWaterAvoidingRandomStrollGoal(FakePlayerEntity p_25987_, double p_25988_) {
		super(p_25987_, p_25988_);
	}

	public HumanoidWaterAvoidingRandomStrollGoal(FakePlayerEntity p_25990_, double p_25991_, float p_25992_) {
		super(p_25990_, p_25991_, p_25992_);
	}

	@Override
	public boolean canUse() {
		FakePlayerEntity fp = (FakePlayerEntity) this.mob;
		if (fp.isSitting() || fp.isMovementManagedByJob()) {
			return false;
		}

		return super.canUse();
	}

	@Override
	public boolean canContinueToUse() {
		FakePlayerEntity fp = (FakePlayerEntity) this.mob;
		if (fp.isSitting() || fp.isMovementManagedByJob()) {
			return false;
		}

		return super.canContinueToUse();
	}
}