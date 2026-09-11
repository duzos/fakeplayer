package dev.duzo.players.entities.goal;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * A tempt goal that lets go while the fake is working.
 *
 * <p>Plain {@link TemptGoal} sits at priority 1 with nothing stopping it taking the movement flag,
 * so a player holding a redstone torch can walk a Miner off its region, a Courier off its route or
 * a Runner away from a delivery it is carrying. Its two neighbours here,
 * {@code MoveTowardsItemsGoal} and {@code HumanoidWaterAvoidingRandomStrollGoal}, are already gated
 * the same way.
 *
 * <p>It also matters for progress tracking: {@code JobHelpers.walkTo} returns early while any path
 * is running, so a job whose movement has been hijacked never advances its stall detection and
 * cannot tell that it is getting nowhere.
 *
 * <p>Named rather than anonymous on purpose: regular Forge 1.20.1 rejects anonymous inner
 * subclasses of remapped Minecraft types, and this has to port to every branch.
 */
public class JobAwareTemptGoal extends TemptGoal {
	private final FakePlayerEntity fake;

	public JobAwareTemptGoal(FakePlayerEntity fake, double speed, Ingredient items, boolean canScare) {
		super(fake, speed, items, canScare);
		this.fake = fake;
	}

	@Override
	public boolean canUse() {
		return !this.fake.isMovementManagedByJob() && super.canUse();
	}

	@Override
	public boolean canContinueToUse() {
		return !this.fake.isMovementManagedByJob() && super.canContinueToUse();
	}
}
