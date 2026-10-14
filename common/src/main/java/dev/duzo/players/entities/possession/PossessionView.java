package dev.duzo.players.entities.possession;

import dev.duzo.players.entities.FakePlayerEntity;

/**
 * What a client does to make a possessed fake look where its possessor's mouse points, instead of where the server last
 * rotated it. Set once by client init through {@link FakePlayerEntity#setViewHook}; common code only sees JDK types here,
 * so a dedicated server never loads a client class.
 */
public interface PossessionView {
	/** Yaw to use for {@code fake}, or NaN when this client is not the one possessing it. */
	float viewYRot(FakePlayerEntity fake, float partialTick);

	/** Pitch to use for {@code fake}, or NaN when this client is not the one possessing it. */
	float viewXRot(FakePlayerEntity fake, float partialTick);
}
