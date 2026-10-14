package dev.duzo.players.compat;

import dev.duzo.players.compat.tide.TideCatches;
import dev.duzo.players.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Safe to call without Tide installed. Nothing here names a Tide type, and TideCatches is only reached behind present(). */
public final class TideFishingBridge {
	private TideFishingBridge() {}

	public static boolean present() { return Services.PLATFORM.isModLoaded("tide"); }

	/** True for Tide's rods, including the vanilla rod when Tide's overrideVanillaRod swaps its item. */
	public static boolean isRod(ItemStack rod) { return present() && TideCatches.isRod(rod); }

	/** Null means fall back to the vanilla loot path; an empty list is a no-catch cycle. */
	public static List<ItemStack> roll(ServerLevel level, ItemStack rod, BlockPos bobber, Vec3 origin,
									   boolean openWater, int luck) {
		return TideCatches.roll(level, rod, bobber, origin, openWater, luck);
	}
}
