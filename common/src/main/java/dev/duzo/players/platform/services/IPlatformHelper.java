package dev.duzo.players.platform.services;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public interface IPlatformHelper {

    /**
     * Gets the name of the current platform
     *
     * @return The name of the current platform.
     */
    String getPlatformName();

    /**
     * Checks if a mod with the given id is loaded.
     *
     * @param modId The mod to check if it is loaded.
     * @return True if the mod is loaded, false otherwise.
     */
    boolean isModLoaded(String modId);

    /**
     * Gets the version string of a loaded mod.
     *
     * @return The version, or null if the mod is not loaded.
     */
    String getModVersion(String modId);

    /**
     * Check if the game is currently in a development environment.
     *
     * @return True if in a development environment, false otherwise.
     */
    boolean isDevelopmentEnvironment();

    /**
     * Gets the name of the environment type as a string.
     *
     * @return The name of the environment type.
     */
    default String getEnvironmentName() {

        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /**
     * Whether land-protection mods and the loader's own break event let this player break the block. Fires the
     * loader's break event with the player as the breaker in the given level, which is the fake's, not necessarily theirs.
     * Call it when the block is about to break, not when digging starts.
     */
    boolean canBreak(ServerLevel level, ServerPlayer player, BlockPos pos);
}