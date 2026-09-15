package dev.duzo.players.entities;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A catch flung back from a bobber sitting in lava. A plain {@link ItemEntity} spawned there burns up
 * before the fisherman can pick it up, so this one ignores lava exactly as the mods that support lava
 * fishing do with their own catches.
 *
 * <p>Named rather than an anonymous subclass on purpose: Forge 1.20.1 rejects anonymous inner
 * subclasses of remapped Minecraft types.
 */
public class LavaProofItemEntity extends ItemEntity {

	public LavaProofItemEntity(Level level, double x, double y, double z, ItemStack stack) {
		super(level, x, y, z, stack);
	}

	@Override
	public void lavaHurt() {
	}

	@Override
	public boolean displayFireAnimation() {
		return false;
	}

	@Override
	public boolean isInvulnerable() {
		return true;
	}
}
