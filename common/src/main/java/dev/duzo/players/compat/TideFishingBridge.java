package dev.duzo.players.compat;

import dev.duzo.players.Constants;
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

	/** Tide 2.1.1 is the first release with the catch API TideCatches links against. */
	private static final int[] MIN_VERSION = {2, 1, 1};
	private static volatile boolean disabled;
	private static Boolean supported;

	public static boolean present() {
		if (disabled || !Services.PLATFORM.isModLoaded("tide")) return false;
		if (supported == null) {
			String version = Services.PLATFORM.getModVersion("tide");
			supported = version != null && atLeast(version, MIN_VERSION);
			if (!supported) Constants.LOG.warn("Tide {} is older than 2.1.1, Tide fishing support is off", version);
		}
		return supported;
	}

	static boolean atLeast(String version, int[] min) {
		String[] parts = version.split("[^0-9]+");
		int idx = 0;
		for (int need : min) {
			while (idx < parts.length && parts[idx].isEmpty()) idx++;
			int have = 0;
			if (idx < parts.length) {
				try { have = Integer.parseInt(parts[idx++]); } catch (NumberFormatException ex) { return false; }
			}
			if (have != need) return have > need;
		}
		return true;
	}

	/** True for Tide's rods, including the vanilla rod when Tide's overrideVanillaRod swaps its item. */
	public static boolean isRod(ItemStack rod) {
		if (!present()) return false;
		try {
			return TideCatches.isRod(rod);
		} catch (RuntimeException | LinkageError ex) {
			// first call that links TideCatches, so a Tide whose classes moved fails here, not in roll
			disabled = true;
			Constants.LOG.warn("Tide rod check threw, disabling Tide support", ex);
			return false;
		}
	}

	/** Null means fall back to the vanilla loot path; an empty list is a no-catch cycle. */
	public static List<ItemStack> roll(ServerLevel level, ItemStack rod, BlockPos bobber, Vec3 origin,
									   boolean openWater, int luck) {
		if (disabled) return null;
		try {
			return TideCatches.roll(level, rod, bobber, origin, openWater, luck);
		} catch (RuntimeException | LinkageError ex) {
			disabled = true;
			Constants.LOG.warn("Tide fishing threw, falling back to vanilla loot and disabling Tide support", ex);
			Constants.traceEvent("tide roll failed: {}", ex.toString());
			return null;
		}
	}
}
