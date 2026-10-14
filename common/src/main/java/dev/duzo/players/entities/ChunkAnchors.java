package dev.duzo.players.entities;

import dev.duzo.players.Constants;
import dev.duzo.players.config.PlayersConfig;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the 3x3 chunks around an anchored fake force-loaded so its job runs with nobody nearby.
 *
 * <p>Vanilla forced chunks are used because they survive a restart: the anchored fake's own chunk
 * loads with the world, the fake ticks, and re-registers here. A ticket would be lost on shutdown
 * and nothing would ever tick the fake again. The 3x3 matters because an entity stops ticking the
 * moment it steps into a chunk that is loaded but not ticking, which is what a lone forced chunk's
 * neighbours are.
 *
 * <p>Each fake remembers which chunks it forced (and so may release), so a chunk already forced by
 * an admin's /forceload or another mod is never unforced, and two fakes sharing a chunk keep it
 * loaded until the last one leaves.
 */
public final class ChunkAnchors {

	private record Key(ResourceKey<Level> dim, long chunk) {}

	private static final Map<Key, Set<UUID>> HOLDERS = new HashMap<>();
	private static final Map<UUID, Long> CENTRES = new HashMap<>();

	private ChunkAnchors() {}

	/** Re-centre on the fake's current chunk. Cheap when it has not changed chunk. */
	public static void update(ServerLevel level, FakePlayerEntity fake) {
		if (!PlayersConfig.get().chunkAnchors) {
			if (!fake.forcedChunks().isEmpty() || CENTRES.containsKey(fake.getUUID())) release(level, fake);
			return;
		}
		long centre = ChunkPos.asLong(fake.blockPosition());
		Long last = CENTRES.get(fake.getUUID());
		if (last != null && last == centre) return;

		LongSet wanted = around(centre);
		if (last != null) {
			for (long chunk : around(last)) {
				if (wanted.contains(chunk)) continue;
				Key key = new Key(level.dimension(), chunk);
				Set<UUID> holders = HOLDERS.get(key);
				if (holders == null) continue;
				holders.remove(fake.getUUID());
				if (holders.isEmpty()) HOLDERS.remove(key);
			}
		}
		LongSet owned = fake.forcedChunks();
		for (long chunk : wanted) {
			HOLDERS.computeIfAbsent(new Key(level.dimension(), chunk), k -> new HashSet<>()).add(fake.getUUID());
			if (owned.contains(chunk)) continue;
			if (level.getForceLoadedChunks().contains(chunk)) continue; // someone else's: never ours to release
			level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true);
			owned.add(chunk);
		}
		LongSet stale = new LongArraySet();
		for (long chunk : owned) if (!wanted.contains(chunk)) stale.add(chunk);
		for (long chunk : stale) letGo(level, fake, chunk);
		CENTRES.put(fake.getUUID(), centre);
		Constants.traceEvent("anchor {} centre {} owns {}", fake.getName().getString(), new ChunkPos(centre), owned.size());
	}

	/** Release every chunk this fake forced, keeping any another anchored fake still stands in. */
	public static void release(ServerLevel level, FakePlayerEntity fake) {
		CENTRES.remove(fake.getUUID());
		LongSet owned = fake.forcedChunks();
		for (long chunk : new LongArraySet(owned)) letGo(level, fake, chunk);
		HOLDERS.values().removeIf(set -> {
			set.remove(fake.getUUID());
			return set.isEmpty();
		});
	}

	/** The fake unloaded with its chunks still forced; forget its centre and holds so it re-registers on reload. */
	public static void unloaded(FakePlayerEntity fake) {
		CENTRES.remove(fake.getUUID());
		HOLDERS.values().removeIf(set -> {
			set.remove(fake.getUUID());
			return set.isEmpty();
		});
	}

	private static void letGo(ServerLevel level, FakePlayerEntity fake, long chunk) {
		Key key = new Key(level.dimension(), chunk);
		Set<UUID> holders = HOLDERS.get(key);
		if (holders != null) {
			holders.remove(fake.getUUID());
			if (holders.isEmpty()) HOLDERS.remove(key);
		}
		fake.forcedChunks().remove(chunk);
		if (holders != null && !holders.isEmpty()) {
			// still needed: hand the chunk to a remaining holder so whoever leaves last unforces it
			for (UUID other : holders) {
				if (level.getEntity(other) instanceof FakePlayerEntity f) {
					f.forcedChunks().add(chunk);
					return;
				}
			}
		}
		level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
	}

	private static LongSet around(long centre) {
		int cx = ChunkPos.getX(centre), cz = ChunkPos.getZ(centre);
		LongSet out = new LongArraySet(9);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) out.add(ChunkPos.asLong(cx + dx, cz + dz));
		}
		return out;
	}
}
