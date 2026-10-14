package dev.duzo.players.entities.speech;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One latch map for everything a fake says: not again for the same kind until the condition has
 * cleared or the cooldown has run out. Shared by {@link FakeSpeech} and the owner alerts so a
 * repeating condition is one line however it is reported.
 */
@ApiStatus.Internal
public final class SpeechLatch {
	/** Long enough that a passing problem says nothing twice, short enough to re-report a real one. */
	public static final long COOLDOWN = 20L * 30L;

	// kinds built from message text are unbounded in principle, so sweep when the map grows
	private static final int SWEEP_AT = 2048;

	private record Key(UUID fake, String kind) {}

	private static final Map<Key, Long> SENT = new HashMap<>();

	private SpeechLatch() {}

	public static boolean isLatched(UUID fake, String kind, long now) {
		Long last = SENT.get(new Key(fake, kind));
		// a clock that moved backwards (world restored from a backup) must not mute this forever
		return last != null && last <= now && now - last < COOLDOWN;
	}

	public static void mark(UUID fake, String kind, long now) {
		if (SENT.size() >= SWEEP_AT) SENT.values().removeIf(last -> last > now || now - last >= COOLDOWN);
		SENT.put(new Key(fake, kind), now);
	}

	public static void clear(UUID fake, String kind) {
		SENT.remove(new Key(fake, kind));
	}

	public static void forget(UUID fake) {
		SENT.keySet().removeIf(key -> key.fake().equals(fake));
	}
}
