package dev.duzo.players.entities.possession;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.entity.ai.control.LookControl;

/** Leaves the fake's rotation to its possessor, who sets it directly; vanilla's look control would level the pitch and recentre the head. */
public class PossessedLookControl extends LookControl {
	private final FakePlayerEntity fake;

	public PossessedLookControl(FakePlayerEntity fake) {
		super(fake);
		this.fake = fake;
	}

	@Override
	public void tick() {
		if (fake.isPossessed()) return;
		super.tick();
	}
}
