package dev.duzo.players.entities.possession;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

/** Walks the fake from held input while it is possessed, and is vanilla's pathing control the rest of the time. */
public class PossessedMoveControl extends MoveControl {
	private final FakePlayerEntity fake;
	private float forward;
	private float strafe;
	private boolean jump;

	public PossessedMoveControl(FakePlayerEntity fake) {
		super(fake);
		this.fake = fake;
	}

	public void setInput(float forward, float strafe, boolean jump) {
		this.forward = forward;
		this.strafe = strafe;
		this.jump = jump;
	}

	@Override
	public void tick() {
		if (!fake.isPossessed()) {
			if (forward != 0.0F || strafe != 0.0F || jump) setInput(0.0F, 0.0F, false);
			super.tick();
			return;
		}
		// whatever a goal asked for before the possession started must not resume when it ends
		this.setWait();
		fake.setSpeed((float) fake.getAttributeValue(Attributes.MOVEMENT_SPEED));
		fake.setZza(forward);
		fake.setXxa(strafe);
		if (jump) fake.getJumpControl().jump();
	}
}
