package dev.duzo.players.api.requests;

import dev.duzo.players.entities.FakePlayerEntity;

import javax.annotation.Nullable;

/**
 * The outcome of a raise. {@code quartermaster} is the board the request landed on, which is what
 * every other facade call needs, so hold this rather than just the {@link ItemRequest}.
 */
public record RaisedRequest(RaiseResult result,
                            @Nullable FakePlayerEntity quartermaster,
                            @Nullable ItemRequest request) {

	public static RaisedRequest failed(RaiseResult result) {
		return new RaisedRequest(result, null, null);
	}

	/** Whether a usable request came back. See {@link RaiseResult#accepted()} for mere acceptance. */
	public boolean ok() {
		return result.hasRequest() && quartermaster != null && request != null;
	}
}
