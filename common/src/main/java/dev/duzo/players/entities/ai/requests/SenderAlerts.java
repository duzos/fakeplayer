package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.speech.FakeSpeech;
import dev.duzo.players.entities.speech.SpeechLatch;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/**
 * Sender side of the alert rule: one message per fake per kind, and not again until the condition
 * has cleared or the cooldown has run out.
 *
 * <p>The receiving half is {@link RequestBoard}'s latches, which stay set until the condition
 * clears. This half exists because a fake raising a request has no board of its own to latch
 * against, and a per-tick raise that keeps failing would otherwise be a per-tick message.
 */
@ApiStatus.Internal
public final class SenderAlerts {
	private SenderAlerts() {}

	/**
	 * Tell the owner, at most once per cooldown for this kind. Nothing is recorded when the message
	 * was not delivered, so a problem that happens while the owner is offline is still reported when
	 * they come back.
	 *
	 * @return true when the owner was actually told.
	 */
	public static boolean alert(ServerLevel level, FakePlayerEntity sender, String kind, String message) {
		long now = level.getGameTime();
		if (SpeechLatch.isLatched(sender.getUUID(), kind, now)) return false;
		if (!RequestRouting.notifyOwner(level, sender, message)) return false;
		SpeechLatch.mark(sender.getUUID(), kind, now);
		return true;
	}

	/** The condition cleared, so the next occurrence is news again rather than a repeat. */
	public static void clear(FakePlayerEntity sender, String kind) {
		SpeechLatch.clear(sender.getUUID(), kind);
	}

	/** Drop everything remembered about a fake that is gone, so the map stays bounded. */
	public static void forget(UUID sender) {
		FakeSpeech.forget(sender);
	}
}
