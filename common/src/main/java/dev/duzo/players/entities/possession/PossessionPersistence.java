package dev.duzo.players.entities.possession;

import dev.duzo.players.mixin.EntityManagerStorageAccessor;
import dev.duzo.players.mixin.EntityStorageAccessor;
import dev.duzo.players.mixin.ServerLevelEntityStorageAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

final class PossessionPersistence {
	private PossessionPersistence() {}

	static boolean playerSaved(MinecraftServer server, UUID player, String token, boolean active) {
		if (token.isEmpty() || PossessionFaults.enabled("fail-player-readback")) return false;
		Path path = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat");
		try {
			CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
			return token.equals(tag.getString("players:possession_token"))
					&& tag.contains(PlayerStash.KEY, Tag.TAG_COMPOUND) == active;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	static Optional<CompoundTag> entitySaved(ServerLevel level, ChunkPos chunk, UUID id) {
		if (PossessionFaults.enabled("fail-entity-readback")) return Optional.empty();
		Object manager = ((ServerLevelEntityStorageAccessor) level).players$storage();
		Object storage = ((EntityManagerStorageAccessor) manager).players$storage();
		SimpleRegionStorage region = ((EntityStorageAccessor) storage).players$storage();
		// Flush has removed pending buffers before this read; the epoch must be present in persisted entity data.
		Optional<CompoundTag> saved = region.read(chunk).join();
		if (saved.isEmpty()) return Optional.empty();
		for (var value : saved.get().getList("Entities", Tag.TAG_COMPOUND)) {
			if (value instanceof CompoundTag entity && id.equals(PossessionLedger.uuid(entity, "UUID"))) return Optional.of(entity);
		}
		return Optional.empty();
	}
}
