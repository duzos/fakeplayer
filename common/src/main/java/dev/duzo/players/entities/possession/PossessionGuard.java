package dev.duzo.players.entities.possession;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;

/**
 * Answers "is this player controlling a fake" for the loader interaction events on either side, without the common
 * server code touching client classes: the client fills in its own answer through {@link #setClientCheck}.
 */
public final class PossessionGuard {
	private static Predicate<Player> clientCheck = player -> false;

	private PossessionGuard() {}

	public static void setClientCheck(Predicate<Player> check) {
		clientCheck = check;
	}

	/** True when a block, item or entity interaction by this player must be refused outright. */
	public static boolean blocks(Player player) {
		if (player instanceof ServerPlayer sp) return PossessionManager.isPossessing(sp);
		return player.level().isClientSide() && clientCheck.test(player);
	}
}
