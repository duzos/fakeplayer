package dev.duzo.players.entities.possession;

import javax.annotation.Nullable;

/** Implemented by {@code ServerPlayer} through a mixin: the stash lives on the player so it is saved in their file. */
public interface PossessionStashHolder {
	@Nullable
	PlayerStash players$getStash();

	void players$setStash(@Nullable PlayerStash stash);

	String players$getSaveToken();
	void players$setSaveToken(String token);

	static @Nullable PlayerStash stashOf(net.minecraft.server.level.ServerPlayer player) {
		return ((PossessionStashHolder) player).players$getStash();
	}

	static void setStash(net.minecraft.server.level.ServerPlayer player, @Nullable PlayerStash stash) {
		((PossessionStashHolder) player).players$setStash(stash);
	}
}
