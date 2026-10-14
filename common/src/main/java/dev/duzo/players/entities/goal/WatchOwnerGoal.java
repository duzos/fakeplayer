package dev.duzo.players.entities.goal;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.SwingAnimation;

import java.util.EnumSet;

/**
 * An idle fake turns to look at its owner when they are close, and gives one wave of the arm when the owner
 * turns up after being away a while. Never runs for a fake that has work to do.
 */
public class WatchOwnerGoal extends Goal {
	private static final double RANGE_SQ = 8.0D * 8.0D;
	/** How long the owner has to have been out of range for their return to be worth a greeting. */
	private static final long AWAY_TICKS = 20L * 10L;
	private static final int MIN_WATCH = 40;
	private static final int WATCH_SPREAD = 60;
	/** Rest between looks, so the fake does not stare at an owner who is just standing there. */
	private static final int COOLDOWN = 100;

	private final FakePlayerEntity fake;
	private Player owner;
	private long awaySince = -1L;
	private long cooldownUntil;
	private boolean greet;
	private int watchTicks;

	public WatchOwnerGoal(FakePlayerEntity fake) {
		this.fake = fake;
		this.setFlags(EnumSet.of(Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		long now = this.fake.level().getGameTime();
		Player found = nearOwner();
		// tracked even while busy, so an owner who left while the fake worked is still greeted when it is free
		if (found == null) {
			if (this.awaySince < 0) this.awaySince = now;
			return false;
		}
		boolean returned = this.awaySince >= 0 && now - this.awaySince >= AWAY_TICKS;
		this.awaySince = -1L;
		if (!this.fake.isIdleNow()) return false;
		if (returned) this.greet = true;
		if (!this.greet && now < this.cooldownUntil) return false;
		this.owner = found;
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return this.watchTicks > 0 && this.fake.isIdleNow() && nearOwner() != null;
	}

	@Override
	public void start() {
		this.watchTicks = MIN_WATCH + this.fake.getRandom().nextInt(WATCH_SPREAD);
		if (this.greet) {
			this.greet = false;
			this.fake.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		}
	}

	@Override
	public void tick() {
		this.watchTicks--;
		Player target = nearOwner();
		if (target != null) this.fake.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ());
	}

	@Override
	public void stop() {
		this.owner = null;
		this.watchTicks = 0;
		this.cooldownUntil = this.fake.level().getGameTime() + COOLDOWN;
	}

	private Player nearOwner() {
		var id = this.fake.getAIState().ownerUUID();
		if (id == null) return null;
		Player player = this.fake.level().getPlayerByUUID(id);
		if (player == null || !player.isAlive() || player.isSpectator() || player.level() != this.fake.level()) return null;
		return this.fake.distanceToSqr(player) <= RANGE_SQ ? player : null;
	}
}
