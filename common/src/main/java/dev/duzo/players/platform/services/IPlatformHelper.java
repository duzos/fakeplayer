package dev.duzo.players.platform.services;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

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

    /**
     * Whether land-protection mods let this player use the block the hit result points at (a toggle, or an item on it).
     * Fires the loader's block-use event for the player with the possession guard stood down, and also requires
     * {@code level.mayInteract}. {@code level} is the fake's, which is not necessarily the player's.
     */
    boolean canUse(ServerLevel level, ServerPlayer player, BlockHitResult hit);

    /**
     * Whether land-protection mods and the loader's own place event let this player put {@code state} at {@code pos}.
     * Fabric has no place event, so there this is spawn protection and the world border only.
     */
    boolean canPlace(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state);
}