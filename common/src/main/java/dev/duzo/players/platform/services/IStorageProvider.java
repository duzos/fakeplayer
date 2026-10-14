package dev.duzo.players.platform.services;

import dev.duzo.players.entities.ai.storage.ExternalStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Finds a modded inventory at a position through the loader's item transfer API: the item handler
 * capability on Forge/NeoForge, {@code ItemStorage.SIDED} on Fabric. Only asked after vanilla's own
 * container lookup has come up empty.
 */
public interface IStorageProvider {

	@Nullable
	ExternalStorage find(Level level, BlockPos pos);
}
