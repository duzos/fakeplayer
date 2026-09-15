package dev.duzo.players.entities.ai;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.storage.loot.LootTable;

import javax.annotation.Nullable;

/**
 * What a rod's fitted tackle does to a cast, read off the rod by the platform's
 * {@link dev.duzo.players.platform.services.ITackleProvider}. A plain rod, or any rod on a loader
 * with no tackle-aware mod installed, is {@link #PLAIN}.
 *
 * @param lureBonus in Lure enchantment levels, so it lands in the same units the Fisherman already uses
 * @param durabilitySkipChance chance that a catch costs the rod no durability
 * @param lavaTable  loot table for a lava catch in an overworld-like dimension, null if lava is not fishable
 * @param netherTable loot table for a lava catch in a dimension with a ceiling
 */
public record Tackle(
		boolean water,
		boolean lava,
		int luckBonus,
		int lureBonus,
		double doubleCatchChance,
		double durabilitySkipChance,
		@Nullable ResourceLocation lavaTable,
		@Nullable ResourceLocation netherTable,
		@Nullable SoundEvent catchSound,
		TackleLook look) {

	public static final Tackle PLAIN = new Tackle(true, false, 0, 0, 0.0D, 0.0D, null, null, null, TackleLook.PLAIN);
}
