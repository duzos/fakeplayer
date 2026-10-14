package dev.duzo.players.entities.possession;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A block placement by a fake. Vanilla orients a placed block from the player in the context, and a fake is not a
 * Player, so the context carries none and answers every facing question from the fake instead.
 */
public class FakePlaceContext extends BlockPlaceContext {
	private final FakePlayerEntity fake;

	public FakePlaceContext(Level level, FakePlayerEntity fake, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
		super(level, null, hand, stack, hit);
		this.fake = fake;
	}

	@Override
	public Direction getNearestLookingDirection() {
		return Direction.orderedByNearest(fake)[0];
	}

	@Override
	public Direction getNearestLookingVerticalDirection() {
		return Direction.getFacingAxis(fake, Direction.Axis.Y);
	}

	@Override
	public Direction[] getNearestLookingDirections() {
		Direction[] ordered = Direction.orderedByNearest(fake);
		if (this.replaceClicked) return ordered;
		Direction opposite = this.getClickedFace().getOpposite();
		int i = 0;
		while (i < ordered.length && ordered[i] != opposite) i++;
		if (i > 0) {
			System.arraycopy(ordered, 0, ordered, 1, i);
			ordered[0] = opposite;
		}
		return ordered;
	}

	@Override
	public Direction getHorizontalDirection() {
		return fake.getDirection();
	}

	@Override
	public float getRotation() {
		return fake.getYRot();
	}
}
