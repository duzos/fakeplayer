package dev.duzo.players.api.requests;

/**
 * Lifecycle of an {@link ItemRequest}.
 *
 * <p>Serialized <b>by name</b>, so a new stage may be inserted without invalidating saved boards.
 * {@code CRAFTING} is the intended insertion point for escalation, between {@link #PENDING} and
 * {@link #SHORTFALL}.
 */
public enum RequestStage {
	/** Raised and waiting for a Quartermaster to resolve it. */
	PENDING,
	/** Assigned to a Runner. */
	DISPATCHED,
	/** The requester has the goods. Terminal, and removed from the board. */
	DELIVERED,
	/** No resolver could source it. Retried on a timer, pruned once the requester is provably gone. */
	SHORTFALL;

	public static RequestStage byName(String name, RequestStage fallback) {
		for (RequestStage stage : values()) {
			if (stage.name().equals(name)) return stage;
		}
		return fallback;
	}
}
