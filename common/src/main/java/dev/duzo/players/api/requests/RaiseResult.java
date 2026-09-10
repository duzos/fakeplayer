package dev.duzo.players.api.requests;

/** Why a {@link FakePlayerRequests#raise} call did or did not take. */
public enum RaiseResult {
	/** A new request is on a Quartermaster's board. */
	RAISED,
	/** An identical open request already existed. The handle points at it, topped up if the new ask was larger. */
	ALREADY_OPEN,
	/**
	 * An identical open request exists on a Quartermaster that has not ticked its job yet, so it
	 * could not be topped up this tick. Nothing was duplicated; try again shortly. The handle
	 * carries the Quartermaster but no request.
	 */
	HOLDER_NOT_READY,
	/** No same-owner Quartermaster in range had a marked pool. */
	NO_QUARTERMASTER,
	/** A Quartermaster was found but its board is at capacity. */
	BOARD_FULL,
	/** The requested stack was empty, or the requester has no owner. */
	INVALID;

	/**
	 * Whether the ask is now, or already was, on a board. Deliberately not called {@code ok()}:
	 * {@link RaisedRequest#ok()} answers the different question of whether a request object came
	 * back, and two same-named predicates disagreeing on one object is a trap.
	 */
	public boolean accepted() {
		return this == RAISED || this == ALREADY_OPEN || this == HOLDER_NOT_READY;
	}

	/** Whether a request object is guaranteed to accompany this result. */
	public boolean hasRequest() {
		return this == RAISED || this == ALREADY_OPEN;
	}
}
