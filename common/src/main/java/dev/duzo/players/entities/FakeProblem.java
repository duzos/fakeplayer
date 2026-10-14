package dev.duzo.players.entities;

import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;

/** What, if anything, is wrong with a working fake. Synced as one byte and drawn as a mark above its head. */
@ApiStatus.Internal
public enum FakeProblem {
	NONE,
	BLOCKED,
	FULL,
	NO_TOOL,
	STUCK;

	public static FakeProblem byId(int id) {
		FakeProblem[] all = values();
		return id >= 0 && id < all.length ? all[id] : NONE;
	}

	/** Sorts a job's blocker line into a problem, so every job reports through one rule. */
	public static FakeProblem fromBlocker(String message) {
		String m = message.toLowerCase(Locale.ROOT);
		if (m.contains("full") || m.contains("cannot fit")) return FULL;
		if (m.contains("no correct tool") || m.contains("no usable") || m.contains("no tool")) return NO_TOOL;
		return BLOCKED;
	}
}
