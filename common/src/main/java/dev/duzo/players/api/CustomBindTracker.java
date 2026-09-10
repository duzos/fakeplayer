package dev.duzo.players.api;

import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Which players have bound the open-menu key clientside. Those clients open the menu from the key instead,
 * so the server stops treating sneak + right-click as an open for them.
 *
 * Weakly keyed so entries go away with the player object; the client re-sends its state whenever it joins,
 * respawns or changes the binding.
 */
public class CustomBindTracker {
	private static final Map<Player, Boolean> BOUND = Collections.synchronizedMap(new WeakHashMap<>());

	private CustomBindTracker() {}

	// The map is only ever filled server-side from the sync packet, so the client needs its own copy or
	// mobInteract disagrees across the wire: the client would consume the right-click that the server ignores.
	private static boolean localBound;

	public static void set(Player player, boolean bound) {
		BOUND.put(player, bound);
	}

	public static void setLocal(boolean bound) {
		localBound = bound;
	}

	public static boolean hasCustomBind(Player player) {
		if (player.level().isClientSide()) return localBound;
		return Boolean.TRUE.equals(BOUND.get(player));
	}
}
