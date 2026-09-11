package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.AIState;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A Runner's receipt for the goods it is carrying: which Quartermaster sent it, which item, how
 * many of that item it already held when assigned, and when.
 *
 * <p>Cargo is {@code inventoryCount(item) - baseline}, so a stack the owner handed the Runner is
 * never mistaken for cargo and the count needs no per-tick write.
 *
 * <p>Presence of a Haul is what "this Runner is busy" means, and because it lives in AIState any
 * Quartermaster can check it without the Runner having ticked yet. That is what makes cross-board
 * double-assignment impossible rather than merely unlikely.
 */
@ApiStatus.Internal
public record Haul(UUID quartermaster, ResourceLocation item, int baseline, long since, int wanted) {
	private static final String TAG = "Haul";

	@Nullable
	public static Haul of(AIState state) {
		CompoundTag params = state.jobParams();
		CompoundTag tag = params.contains(TAG) ? params.getCompound(TAG) : new CompoundTag();
		if (tag.isEmpty()) return null;
		int[] raw = tag.contains("Qm") ? tag.getIntArray("Qm") : null;
		if (raw == null || raw.length != 4) return null;
		ResourceLocation item = ResourceLocation.tryParse(tag.contains("Item") ? tag.getString("Item") : "");
		if (item == null) return null;
		return new Haul(UUIDUtil.uuidFromIntArray(raw), item,
				tag.contains("Base") ? tag.getInt("Base") : 0, tag.contains("Since") ? tag.getLong("Since") : 0L, tag.contains("Want") ? tag.getInt("Want") : 0);
	}

	public static boolean isBusy(FakePlayerEntity runner) {
		return of(runner.getAIState()) != null;
	}

	/**
	 * Assign this Runner, capturing what it already holds so cargo can be derived later.
	 *
	 * @return false when the receipt could not be stored, in which case the Runner is NOT marked
	 *         busy and must not be dispatched.
	 */
	public static boolean write(FakePlayerEntity runner, UUID quartermaster, ResourceLocation item, int wanted, long now) {
		return write(runner, quartermaster, item, countOf(runner, item), now, wanted);
	}

	/** Write an explicit baseline, used to correct one that has gone stale without resetting the clock. */
	public static boolean write(FakePlayerEntity runner, UUID quartermaster, ResourceLocation item, int baseline, long since, int wanted) {
		return runner.mutateAIState(state -> {
			CompoundTag tag = new CompoundTag();
			tag.putIntArray("Qm", UUIDUtil.uuidToIntArray(quartermaster));
			tag.putString("Item", item.toString());
			tag.putInt("Base", baseline);
			tag.putLong("Since", since);
			tag.putInt("Want", wanted);
			CompoundTag params = state.jobParams();
			params.put(TAG, tag);
			state.setJobParams(params);
		});
	}

	/** @return false when the receipt could not be removed, leaving the Runner marked busy. */
	public static boolean clear(FakePlayerEntity runner) {
		return runner.mutateAIState(state -> {
			CompoundTag params = state.jobParams();
			params.remove(TAG);
			state.setJobParams(params);
		});
	}

	/**
	 * Units of this Haul's item carried for the request, never counting what the Runner owned before.
	 *
	 * <p>If the count has fallen below the baseline the owner took items out of the Runner, so the
	 * baseline is corrected down. Saturating at zero instead made the Runner collect until full,
	 * draining the pool, and then return none of it.
	 */
	public int cargo(FakePlayerEntity runner) {
		int held = countOf(runner, item);
		// bounded by the size of the request this receipt was written for. Without it a stale
		// baseline (the fake was re-jobbed and has since accumulated the item by other means)
		// made every unit it holds look like cargo owed to the pool.
		if (wanted > 0 && held - baseline > wanted) return wanted;
		if (held < baseline) {
			if (!write(runner, quartermaster, item, held, since, wanted)) {
				// the correction could not be stored, so this will be retried every tick. Say so
				// once per occurrence rather than failing silently.
				Constants.LOG.warn("Could not correct the haul baseline for {}: its AIState is too large to sync",
						runner.getUUID());
			}
			return 0;
		}
		return held - baseline;
	}

	public static int countOf(FakePlayerEntity runner, ResourceLocation item) {
		int n = 0;
		SimpleContainer inv = runner.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item)) n += stack.getCount();
		}
		return n;
	}
}
