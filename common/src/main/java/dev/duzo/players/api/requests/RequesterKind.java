package dev.duzo.players.api.requests;

/** Whether a request was raised by a fake player or by a real one. */
public enum RequesterKind {
	FAKE,
	PLAYER;

	public static RequesterKind byName(String name, RequesterKind fallback) {
		for (RequesterKind kind : values()) {
			if (kind.name().equals(name)) return kind;
		}
		return fallback;
	}
}
