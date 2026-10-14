package dev.duzo.players.entities.speech;

import dev.duzo.players.Constants;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one way a fake says something publicly. Gates by verbosity, latches per kind, caps the rate,
 * then routes to the speech bubble or to chat depending on {@code speechBubbles}. Owner-only
 * alerts do not come through here: they stay chat lines to one player.
 */
@ApiStatus.Internal
public final class FakeSpeech {
	/** A fake never emits more than one line in this window, whatever the kinds. */
	private static final long MIN_GAP = 40L;

	// the latch map is shared with the owner alerts, so a speech kind must not collide with theirs
	private static final String KIND_PREFIX = "say:";

	private static final Map<UUID, Long> LAST_LINE = new HashMap<>();

	private FakeSpeech() {}

	/**
	 * @param kind what the line is about, latched for 30 seconds per fake. Use a distinct kind per
	 *             condition, not per message, so a changed message about the same thing stays quiet.
	 * @return true when the line was actually spoken.
	 */
	public static boolean say(FakePlayerEntity fake, Verbosity verbosity, String kind, String text) {
		if (fake.level().isClientSide()) return false;
		kind = KIND_PREFIX + kind;
		long now = fake.level().getGameTime();
		UUID id = fake.getUUID();

		PlayersConfig config = PlayersConfig.get();
		if (!verbosity.allowedAt(Verbosity.parseConfig(config.speechVerbosity))) {
			Constants.traceEvent("speech muted {} kind={} verbosity={}", id, kind, verbosity);
			return false;
		}
		// a line a player asked for is never "a repeat"
		if (verbosity != Verbosity.ALWAYS && SpeechLatch.isLatched(id, kind, now)) {
			Constants.traceEvent("speech latched {} kind={}", id, kind);
			return false;
		}
		Long last = LAST_LINE.get(id);
		if (last != null && last <= now && now - last < MIN_GAP) {
			Constants.traceEvent("speech rate-limited {} kind={}", id, kind);
			return false;
		}

		SpeechLatch.mark(id, kind, now);
		LAST_LINE.put(id, now);
		if (config.speechBubbles) {
			fake.speak(text);
			Constants.traceEvent("speech bubbled {} kind={}: {}", id, kind, text);
		} else {
			fake.sendChat(text);
			Constants.traceEvent("speech chatted {} kind={}: {}", id, kind, text);
		}
		return true;
	}

	/** The condition cleared, so the next occurrence is news again rather than a repeat. */
	public static void clear(FakePlayerEntity fake, String kind) {
		SpeechLatch.clear(fake.getUUID(), KIND_PREFIX + kind);
	}

	/** Drop everything remembered about a fake that is gone, so the maps stay bounded. */
	public static void forget(UUID fake) {
		SpeechLatch.forget(fake);
		LAST_LINE.remove(fake);
	}
}
