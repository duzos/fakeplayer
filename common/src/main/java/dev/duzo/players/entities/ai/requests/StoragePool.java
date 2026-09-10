package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.entities.ai.AIState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The containers a Quartermaster treats as one logical pool, stored like Guard's patrol points.
 *
 * <p>Read by the client screen as well as the server, hence public, but not an addon surface:
 * {@code toggle} rewrites any fake's pool with no ownership check. Use
 * {@code FakePlayerRequests.poolOf} instead.
 */
@ApiStatus.Internal
public final class StoragePool {
	private static final String TAG_POOL = "Pool";

	private StoragePool() {}

	/**
	 * Chests and barrels yes, sided containers no. HopperBlockEntity.addItem with a null direction
	 * skips the sided-slot check, so a pooled furnace would accept returned goods into its fuel or
	 * output slot.
	 */
	public static boolean isPoolable(Level level, BlockPos pos) {
		Container container = HopperBlockEntity.getContainerAt(level, pos);
		return container != null && !(container instanceof WorldlyContainer);
	}

	public static List<BlockPos> read(AIState state) {
		CompoundTag tag = state.jobParams();
		long[] raw = tag.contains(TAG_POOL) ? tag.getLongArray(TAG_POOL) : new long[0];
		List<BlockPos> out = new ArrayList<>(raw.length);
		for (long l : raw) out.add(BlockPos.of(l));
		return out;
	}

	/** Adds the container, or removes it if already pooled. Returns true when it was added. */
	public static boolean toggle(AIState state, BlockPos pos) {
		CompoundTag params = state.jobParams();
		long[] cur = params.contains(TAG_POOL) ? params.getLongArray(TAG_POOL) : new long[0];
		long key = pos.asLong();
		long[] without = Arrays.stream(cur).filter(l -> l != key).toArray();
		if (without.length != cur.length) {
			if (without.length == 0) params.remove(TAG_POOL);
			else params.putLongArray(TAG_POOL, without);
			state.setJobParams(params);
			return false;
		}
		long[] next = Arrays.copyOf(cur, cur.length + 1);
		next[cur.length] = key;
		params.putLongArray(TAG_POOL, next);
		state.setJobParams(params);
		return true;
	}
}
