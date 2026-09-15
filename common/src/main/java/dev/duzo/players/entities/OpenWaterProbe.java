package dev.duzo.players.entities;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.Level;

/**
 * A {@link FishingHook} that exists only to answer the {@code fishing_hook} loot predicate, which the
 * top-level {@code gameplay/fishing} table gates treasure behind and which calls
 * {@link #isOpenWaterFishing()} virtually.
 *
 * <p>It is never added to the level, so it never ticks and never looks for a player owner: a
 * {@link FakeFishingHook} is a plain Projectile owned by a Mob, which vanilla's hook would discard on
 * its first tick. The open-water answer comes from the Fisherman's own test instead.
 */
public class OpenWaterProbe extends FishingHook {
	private final boolean openWater;

	public OpenWaterProbe(Level level, boolean openWater) {
		super(EntityTypes.FISHING_BOBBER, level);
		this.openWater = openWater;
	}

	@Override
	public boolean isOpenWaterFishing() {
		return this.openWater;
	}
}
