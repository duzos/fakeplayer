package dev.duzo.players.api.requests;

/** Why a {@link FakePlayerRequests#raise} call did or did not take. */
public enum RaiseResult {
	/** A new request is on a Quartermaster's board. */
	RAISED,
	/** An identical open request already existed. The handle points at it, topped up if the new ask was larger. */
	ALREADY_OPEN,
	/** No same-owner Quartermaster in range had a marked pool. */
	NO_QUARTERMASTER,
	/** A Quartermaster was found but its board is at capacity. */
	BOARD_FULL,
	/** The requested stack was empty, or the requester has no owner. */
	INVALID;

	public boolean ok() {
		return this == RAISED || this == ALREADY_OPEN;
	}
}
