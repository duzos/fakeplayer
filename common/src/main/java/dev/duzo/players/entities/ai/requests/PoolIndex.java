package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.JobHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A Quartermaster's cached view of its pool: item id to the slots holding it. Rebuilt when dirty
 * and on an interval, and decremented in place when a Runner collects, so resolving a request does
 * not rescan the storeroom.
 *
 * <p>Keyed by dimension as well as UUID, because pool positions are bare longs and a UUID-only key
 * would index the wrong dimension's blocks.
 */
@ApiStatus.Internal
public final class PoolIndex {
	private record CacheKey(ResourceKey<Level> level, UUID quartermaster) {}

	private static final Map<CacheKey, PoolIndex> CACHE = new HashMap<>();

	/** One stack's worth of one item in a known container. */
	public record Loc(BlockPos pos, int count) {}

	private final Map<ResourceLocation, Integer> counts = new HashMap<>();
	private final Map<ResourceLocation, List<Loc>> locations = new HashMap<>();
	private boolean dirty;
	private long rebuiltAt;

	// Never rebuilt yet, but staggered, so a base with many quartermasters does not rebuild them
	// all on one tick. Staleness alone drives the first build; a `dirty = true` initializer would
	// overwrite the seed on the first access and defeat the stagger entirely.
	private PoolIndex(long seed) {
		this.dirty = false;
		this.rebuiltAt = seed;
	}

	/** The index for this Quartermaster in this level, rebuilt first if stale. */
	public static PoolIndex of(ServerLevel level, FakePlayerEntity quartermaster) {
		CacheKey key = new CacheKey(level.dimension(), quartermaster.getUUID());
		PoolIndex index = CACHE.computeIfAbsent(key, k -> new PoolIndex(
				level.getGameTime() - interval() - Math.floorMod(k.quartermaster().hashCode(), interval())));
		index.ensure(level, quartermaster);
		return index;
	}

	public static void markDirty(ServerLevel level, UUID quartermaster) {
		PoolIndex index = CACHE.get(new CacheKey(level.dimension(), quartermaster));
		if (index != null) index.dirty = true;
	}

	public static void forget(ServerLevel level, UUID quartermaster) {
		CACHE.remove(new CacheKey(level.dimension(), quartermaster));
	}

	private static int interval() {
		return Math.max(20, PlayersConfig.get().requestIndexInterval);
	}

	public int count(ResourceLocation item) {
		return counts.getOrDefault(item, 0);
	}

	/** Every item the pool holds, with its total count. A snapshot, safe to hand to a packet. */
	public Map<ResourceLocation, Integer> contents() {
		return Map.copyOf(counts);
	}

	public List<Loc> locations(ResourceLocation item) {
		return List.copyOf(locations.getOrDefault(item, List.of()));
	}

	/**
	 * Record a collection without forcing a rebuild. Counts are re-derived from the surviving
	 * locations rather than decremented separately, so the two maps cannot disagree even when a
	 * take spans slots or the index was already stale.
	 */
	public void noteTaken(ResourceLocation item, BlockPos from, int taken) {
		if (taken <= 0) return;
		List<Loc> locs = locations.get(item);
		if (locs == null) {
			counts.remove(item);
			return;
		}

		int owed = taken;
		List<Loc> kept = new ArrayList<>(locs.size());
		for (Loc loc : locs) {
			if (owed <= 0 || !loc.pos().equals(from)) {
				kept.add(loc);
				continue;
			}
			int drop = Math.min(owed, loc.count());
			owed -= drop;
			if (loc.count() - drop > 0) kept.add(new Loc(loc.pos(), loc.count() - drop));
		}

		int total = 0;
		for (Loc loc : kept) total += loc.count();
		if (total <= 0) {
			counts.remove(item);
			locations.remove(item);
		} else {
			counts.put(item, total);
			locations.put(item, kept);
		}
	}

	/** The other half of a double chest at this position, or null if it is not one. */
	@Nullable
	private static BlockPos doubleChestPartner(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)) return null;
		if (!state.hasProperty(ChestBlock.TYPE)) return null;
		ChestType type = state.getValue(ChestBlock.TYPE);
		if (type == ChestType.SINGLE) return null;
		return pos.relative(ChestBlock.getConnectedDirection(state)).immutable();
	}

	private void ensure(ServerLevel level, FakePlayerEntity quartermaster) {
		long now = level.getGameTime();
		// a clock that moved backwards (world restored, different level) must not freeze rebuilds
		boolean stale = rebuiltAt > now || now - rebuiltAt >= interval();
		if (!dirty && !stale) return;
		rebuild(level, quartermaster);
		rebuiltAt = now;
		dirty = false;
		RequestDebug.event(quartermaster, "index", "rebuilt: {} kinds, {}",
				counts.size(), counts.isEmpty() ? "empty" : counts.toString());
	}

	private void rebuild(ServerLevel level, FakePlayerEntity quartermaster) {
		counts.clear();
		locations.clear();
		Set<BlockPos> counted = new HashSet<>();
		for (BlockPos pos : StoragePool.read(quartermaster.getAIState())) {
			Container container = JobHelpers.containerAt(level, pos);
			if (container == null) continue;
			// re-checked here, not just at marking time: a pooled chest may since have been
			// replaced by a furnace, whose fuel and output slots are not storage
			if (container instanceof WorldlyContainer) continue;
			// Marking both halves of a double chest is the natural gesture, and getContainerAt
			// returns the whole merged inventory for either half, so counting both would double
			// every stack. Identity comparison does not work: CompoundContainer is freshly
			// allocated on each call. Skip a half whose partner is already counted.
			if (counted.contains(pos)) continue;
			counted.add(pos.immutable());
			BlockPos partner = doubleChestPartner(level, pos);
			if (partner != null) counted.add(partner);

			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty()) continue;
				ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
				counts.merge(id, stack.getCount(), Integer::sum);
				locations.computeIfAbsent(id, k -> new ArrayList<>())
						.add(new Loc(pos.immutable(), stack.getCount()));
			}
		}
	}
}
