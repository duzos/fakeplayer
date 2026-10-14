package dev.duzo.players.entities.possession;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/** An item use by a fake. UseOnContext only offers the player-less constructor to subclasses, and a named class keeps Forge's CI happy. */
public class FakeUseContext extends UseOnContext {
	public FakeUseContext(Level level, ItemStack stack, BlockHitResult hit) {
		super(level, null, InteractionHand.MAIN_HAND, stack, hit);
	}
}
