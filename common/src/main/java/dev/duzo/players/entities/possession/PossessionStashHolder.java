package dev.duzo.players.entities.possession;

import org.jspecify.annotations.Nullable;

/** Implemented by {@code ServerPlayer} through a mixin: the stash lives on the player so it is saved in their file. */
public interface PossessionStashHolder {
	@Nullable
	PlayerStash players$getStash();

	void players$setStash(@Nullable PlayerStash stash);

	static @Nullable PlayerStash stashOf(net.minecraft.server.level.ServerPlayer player) {
		return ((PossessionStashHolder) player).players$getStash();
	}

	static void setStash(net.minecraft.server.level.ServerPlayer player, @Nullable PlayerStash stash) {
		((PossessionStashHolder) player).players$setStash(stash);
	}
}
