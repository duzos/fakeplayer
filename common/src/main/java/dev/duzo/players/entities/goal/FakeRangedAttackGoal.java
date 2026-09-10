package dev.duzo.players.entities.goal;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.RangedWeapon;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Skeleton-style ranged combat. Ported from vanilla's RangedBowAttackGoal, which cannot be reused directly
 * because it is bounded on Monster, with the pillager crossbow state machine and a trident case folded in.
 */
public class FakeRangedAttackGoal extends Goal {
	private static final int BOW_DRAW_TICKS = 20;
	private static final int BOW_INTERVAL = 20;
	private static final int TRIDENT_INTERVAL = 40;
	/** Vanilla's minimum trident charge before a throw counts. */
	private static final int TRIDENT_WINDUP_TICKS = 10;
	private static final int STRAFE_FLIP_TICKS = 20;
	/** How long the fake keeps hunting after losing sight before it gives up on the shot. */
	private static final int BLIND_GIVE_UP_TICKS = -60;

	private final FakePlayerEntity mob;
	private final double speedModifier;
	private final float attackRadiusSqr;

	private int attackTime = -1;
	private int seeTime;
	private boolean strafingClockwise;
	private boolean strafingBackwards;
	private int strafingTime = -1;

	private CrossbowState crossbowState = CrossbowState.UNCHARGED;
	private int crossbowDelay;

	public FakeRangedAttackGoal(FakePlayerEntity mob, double speedModifier, float attackRadius) {
		this.mob = mob;
		this.speedModifier = speedModifier;
		this.attackRadiusSqr = attackRadius * attackRadius;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return this.mob.getTarget() != null
				&& !this.mob.isSitting()
				&& this.mob.allowsCombatMovement()
				&& this.mob.getUsableRangedWeapon() != null;
	}

	@Override
	public boolean canContinueToUse() {
		return (this.canUse() || !this.mob.getNavigation().isDone()) && this.mob.getUsableRangedWeapon() != null;
	}

	@Override
	public void start() {
		super.start();
		this.mob.setAggressive(true);
	}

	@Override
	public void stop() {
		super.stop();
		this.mob.setAggressive(false);
		this.seeTime = 0;
		this.attackTime = -1;
		this.strafingTime = -1;
		this.mob.stopUsingItem();
		this.mob.setChargingCrossbow(false);
		this.releaseCrossbowCharge();
		this.crossbowState = CrossbowState.UNCHARGED;
	}

	// A crossbow left loaded when the goal stops keeps the fake in the aiming pose forever, looking frozen
	// mid-draw. Vanilla's RangedCrossbowAttackGoal clears the charge for the same reason.
	private void releaseCrossbowCharge() {
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack held = this.mob.getItemInHand(hand);
			if (held.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(held)) {
				CrossbowItem.setCharged(held, false);
			}
		}
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		LivingEntity target = this.mob.getTarget();
		if (target == null) return;

		RangedWeapon weapon = this.mob.getUsableRangedWeapon();
		if (weapon == null) return;

		double distSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
		boolean seen = this.mob.getSensing().hasLineOfSight(target);
		if (seen != this.seeTime > 0) {
			this.seeTime = 0;
		}
		if (seen) {
			this.seeTime++;
		} else {
			this.seeTime--;
		}

		this.moveAndStrafe(target, distSqr);

		switch (weapon) {
			case BOW -> this.tickBow(target, seen);
			case CROSSBOW -> this.tickCrossbow(seen);
			case TRIDENT -> this.tickTrident(target, seen);
		}
	}

	// Hold at range once the target has been in sight for a second, then circle it rather than standing still.
	private void moveAndStrafe(LivingEntity target, double distSqr) {
		if (distSqr <= (double) this.attackRadiusSqr && this.seeTime >= 20) {
			this.mob.getNavigation().stop();
			this.strafingTime++;
		} else {
			this.mob.getNavigation().moveTo(target, this.speedModifier);
			this.strafingTime = -1;
		}

		if (this.strafingTime >= STRAFE_FLIP_TICKS) {
			if (this.mob.getRandom().nextFloat() < 0.3F) {
				this.strafingClockwise = !this.strafingClockwise;
			}
			if (this.mob.getRandom().nextFloat() < 0.3F) {
				this.strafingBackwards = !this.strafingBackwards;
			}
			this.strafingTime = 0;
		}

		if (this.strafingTime > -1) {
			if (distSqr > (double) (this.attackRadiusSqr * 0.75F)) {
				this.strafingBackwards = false;
			} else if (distSqr < (double) (this.attackRadiusSqr * 0.25F)) {
				this.strafingBackwards = true;
			}
			this.mob.getMoveControl().strafe(this.strafingBackwards ? -0.5F : 0.5F, this.strafingClockwise ? 0.5F : -0.5F);
			this.mob.lookAt(target, 30.0F, 30.0F);
		} else {
			this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
		}
	}

	private void tickBow(LivingEntity target, boolean seen) {
		if (this.mob.isUsingItem()) {
			if (!seen && this.seeTime < BLIND_GIVE_UP_TICKS) {
				this.mob.stopUsingItem();
				return;
			}
			if (seen) {
				int drawn = this.mob.getTicksUsingItem();
				if (drawn >= BOW_DRAW_TICKS) {
					this.mob.stopUsingItem();
					this.mob.performRangedAttack(target, BowItem.getPowerForTime(drawn));
					this.attackTime = BOW_INTERVAL;
				}
			}
			return;
		}

		if (--this.attackTime <= 0 && this.seeTime >= BLIND_GIVE_UP_TICKS) {
			InteractionHand hand = this.handFor(RangedWeapon.BOW);
			if (hand != null) this.mob.startUsingItem(hand);
		}
	}

	private void tickCrossbow(boolean seen) {
		InteractionHand hand = this.handFor(RangedWeapon.CROSSBOW);
		if (hand == null) return;

		switch (this.crossbowState) {
			case UNCHARGED -> {
				this.mob.startUsingItem(hand);
				this.crossbowState = CrossbowState.CHARGING;
				this.mob.setChargingCrossbow(true);
			}
			case CHARGING -> {
				if (!this.mob.isUsingItem()) {
					this.crossbowState = CrossbowState.UNCHARGED;
					this.mob.setChargingCrossbow(false);
				} else if (this.mob.getTicksUsingItem() >= CrossbowItem.getChargeDuration(this.mob.getUseItem())) {
					this.mob.releaseUsingItem();
					this.crossbowState = CrossbowState.CHARGED;
					this.crossbowDelay = 20 + this.mob.getRandom().nextInt(20);
					this.mob.setChargingCrossbow(false);
				}
			}
			case CHARGED -> {
				if (--this.crossbowDelay <= 0) {
					this.crossbowState = CrossbowState.READY_TO_ATTACK;
				}
			}
			case READY_TO_ATTACK -> {
				if (seen) {
					this.mob.performCrossbowAttack(this.mob, 1.6F);
					this.crossbowState = CrossbowState.UNCHARGED;
				}
			}
		}
	}

	// Wound up like a bow so the fake visibly raises the trident before throwing. stopUsingItem rather than
	// releaseUsingItem on purpose: releasing would run TridentItem's own throw and consume the trident.
	private void tickTrident(LivingEntity target, boolean seen) {
		if (this.mob.isUsingItem()) {
			if (!seen && this.seeTime < BLIND_GIVE_UP_TICKS) {
				this.mob.stopUsingItem();
				return;
			}
			if (seen && this.mob.getTicksUsingItem() >= TRIDENT_WINDUP_TICKS) {
				this.mob.stopUsingItem();
				this.mob.performRangedAttack(target, 1.6F);
				this.attackTime = TRIDENT_INTERVAL;
			}
			return;
		}

		if (--this.attackTime <= 0 && this.seeTime >= BLIND_GIVE_UP_TICKS) {
			InteractionHand hand = this.handFor(RangedWeapon.TRIDENT);
			if (hand != null) this.mob.startUsingItem(hand);
		}
	}

	@Nullable
	private InteractionHand handFor(RangedWeapon weapon) {
		for (InteractionHand hand : InteractionHand.values()) {
			if (RangedWeapon.of(this.mob.getItemInHand(hand)) == weapon) return hand;
		}
		return null;
	}

	private enum CrossbowState {
		UNCHARGED,
		CHARGING,
		CHARGED,
		READY_TO_ATTACK
	}
}
