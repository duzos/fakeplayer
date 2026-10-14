package dev.duzo.players.compat.tide;

import com.li64.tide.Tide;
import com.li64.tide.compat.seasons.SeasonsCompat;
import com.li64.tide.data.TideFishingManager;
import com.li64.tide.data.fishing.CatchResult;
import com.li64.tide.data.fishing.FishingContext;
import com.li64.tide.registries.TideEntityTypes;
import com.li64.tide.registries.entities.misc.fishing.TideFishingHook;
import com.li64.tide.registries.items.TideFishingRodItem;
import com.li64.tide.util.TideUtils;
import dev.duzo.players.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Every Tide type the mod touches lives in this class. Only load it behind TideFishingBridge.present(), and keep
 * Tide types out of its public signatures so the bridge never links against them.
 *
 * Tide's own pipeline needs a player and a spawned hook, so this only borrows its catch selector: it rolls
 * selectCatch against a context built from the fake's real surroundings. Bait, the journal, the minigame and the
 * rod bonuses are not applied.
 */
public final class TideCatches {
	/** Crates are delivered by a spawned hook entity, so a crate roll is thrown back this many times. */
	static final int MAX_CRATE_REROLLS = 8;

	private TideCatches() {}

	public static boolean isRod(ItemStack rod) {
		return rod.getItem() instanceof TideFishingRodItem;
	}

	/**
	 * The catch for one reel-in, or null when Tide cannot roll yet (its selector is built when the server starts),
	 * in which case the caller falls back to vanilla loot. An empty list is a no-catch cycle.
	 */
	public static List<ItemStack> roll(ServerLevel level, ItemStack rod, BlockPos bobber, Vec3 origin,
									   boolean openWater, int luck) {
		TideFishingManager manager = Tide.FISHING_MANAGER;
		if (manager == null) return null;

		ProbeHook hook = new ProbeHook(level, rod, origin, openWater);
		var biome = level.getBiome(bobber);
		// A fake only fishes water here: lava and void need rod tackle that is not emulated.
		FishingContext context = new FishingContext(level, hook, rod, level.getRandom(), origin, bobber, luck, "water",
				biome, TideUtils.findClosestNonWaterBiome(level, bobber, 12, 2).orElse(biome), level.dimension(),
				Mth.clamp(TideUtils.getTemperatureAt(bobber, level), -1.0F, 1.0F), level.getMoonPhase(),
				SeasonsCompat.getSeason(level));

		for (int attempt = 0; attempt <= MAX_CRATE_REROLLS; attempt++) {
			CatchResult result = manager.selectCatch(context);
			if (result.isCrate()) {
				Constants.traceEvent("tide catch attempt {}: crate, rerolling", attempt + 1);
				continue;
			}
			if (result.isEmpty()) {
				Constants.traceEvent("tide catch attempt {}: nothing valid at {} (openWater={})", attempt + 1, bobber, openWater);
				return List.of();
			}
			List<ItemStack> items = new ArrayList<>(result.items().size());
			for (ItemStack stack : result.items()) items.add(stack.copy());
			Constants.traceEvent("tide catch attempt {}: {} at {} (openWater={}, luck={})",
					attempt + 1, items, bobber, openWater, luck);
			return items;
		}
		Constants.traceEvent("tide catch: {} crate rolls in a row, no catch this cycle", MAX_CRATE_REROLLS + 1);
		return List.of();
	}

	/**
	 * An unspawned hook that only answers the questions Tide's conditions ask: the real rod (rod, getHook and the
	 * other tackle getters read the rod field) and the Fisherman's own open water result. It has no owner, so
	 * getContext must never be called on it.
	 */
	private static final class ProbeHook extends TideFishingHook {
		private final boolean openWaterAnswer;

		ProbeHook(ServerLevel level, ItemStack rod, Vec3 origin, boolean openWater) {
			super(TideEntityTypes.FISHING_BOBBER, level);
			this.rod = rod;
			this.openWaterAnswer = openWater;
			setPos(origin.x, origin.y, origin.z);
		}

		@Override
		public boolean isOpenWaterFishing() { return openWaterAnswer; }
	}
}
