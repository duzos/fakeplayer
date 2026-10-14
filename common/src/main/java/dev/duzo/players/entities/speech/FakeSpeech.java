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
 * then sends it to chat as the fake's own line and, unless disabled, as a speech bubble. Owner-only
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
		return deliver(fake, verbosity, kind, text) == Outcome.SPOKEN;
	}

	/**
	 * For callers that latch a one-shot flag. True when the line was spoken or when retrying cannot help
	 * (muted by verbosity, or the same kind spoke recently). False only when the rate limit dropped it, so
	 * the caller leaves its flag unset and tries again.
	 */
	public static boolean settle(FakePlayerEntity fake, Verbosity verbosity, String kind, String text) {
		return deliver(fake, verbosity, kind, text) != Outcome.RATE_LIMITED;
	}

	private enum Outcome { SPOKEN, MUTED, LATCHED, RATE_LIMITED }

	private static Outcome deliver(FakePlayerEntity fake, Verbosity verbosity, String kind, String text) {
		if (fake.level().isClientSide()) return Outcome.MUTED;
		kind = KIND_PREFIX + kind;
		long now = fake.level().getGameTime();
		UUID id = fake.getUUID();

		PlayersConfig config = PlayersConfig.get();
		if (!verbosity.allowedAt(Verbosity.parseConfig(config.speechVerbosity))) {
			Constants.traceEvent("speech muted {} kind={} verbosity={}", id, kind, verbosity);
			return Outcome.MUTED;
		}
		boolean always = verbosity == Verbosity.ALWAYS;
		// a line a player asked for is never "a repeat" and never rate limited
		if (!always) {
			if (SpeechLatch.isLatched(id, kind, now)) {
				Constants.traceEvent("speech latched {} kind={}", id, kind);
				return Outcome.LATCHED;
			}
			Long last = LAST_LINE.get(id);
			if (last != null && last <= now && now - last < MIN_GAP) {
				Constants.traceEvent("speech rate-limited {} kind={}", id, kind);
				return Outcome.RATE_LIMITED;
			}
		}

		SpeechLatch.mark(id, kind, now);
		LAST_LINE.put(id, now);
		// speech mirrors chat: the bubble is the extra, unless the config says it replaces the chat line
		if (config.speechBubbles) fake.speak(text);
		if (always || !config.speechBubbles || !config.speechBubblesReplaceChat) fake.sendChat(text);
		Constants.traceEvent("speech spoken {} kind={}: {}", id, kind, text);
		return Outcome.SPOKEN;
	}

	/** The condition cleared, so the next occurrence is news again rather than a repeat. */
	public static void clear(FakePlayerEntity fake, String kind) {
		SpeechLatch.clear(fake.getUUID(), KIND_PREFIX + kind);
	}

	/** A job's blocker line cleared, so the same blocker is news again the next time it comes up. */
	public static void clearBlocked(FakePlayerEntity fake, String blocker) {
		if (blocker != null && !blocker.isEmpty()) clear(fake, "blocked:" + blocker);
	}

	/** Drop everything remembered about a fake that is gone, so the maps stay bounded. */
	public static void forget(UUID fake) {
		SpeechLatch.forget(fake);
		LAST_LINE.remove(fake);
	}
}
