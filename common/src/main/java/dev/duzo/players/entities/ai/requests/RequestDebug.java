package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.Constants;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.entities.FakePlayerEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * TEMPORARY playtest instrumentation. Remove before release.
 *
 * <p>Deduped per fake <b>and per kind</b>: a job emitting two lines a tick defeats a per-fake-only
 * filter and floods at 20 lines a second. Only a changed message for that (fake, kind) pair is
 * printed, so a steady state is silent and every transition is visible.
 */
public final class RequestDebug {
	private static final Map<String, String> LAST = new HashMap<>();

	private RequestDebug() {}

	/** Log only when this (fake, kind) pair's message has changed since last time. */
	public static void state(FakePlayerEntity fake, String kind, String format, Object... args) {
		String message = format(format, args);
		String key = fake.getUUID() + "|" + kind;
		if (message.equals(LAST.get(key))) return;
		LAST.put(key, message);
		Constants.debug("[fpdebug] {} {} {}: {}", fake.getAIState().job(), shortId(fake.getUUID()), kind, message);
	}

	/** Log every time, for one-shot events that cannot repeat per tick. */
	public static void event(FakePlayerEntity fake, String kind, String format, Object... args) {
		Constants.debug("[fpdebug] {} {} {}: {}", fake.getAIState().job(), shortId(fake.getUUID()), kind,
				format(format, args));
	}

	public static String describe(@Nullable ItemRequest request) {
		if (request == null) return "none";
		return request.key().item() + " x" + request.remaining() + "/" + request.wanted()
				+ " " + request.stage()
				+ (request.assignee() == null ? " unassigned" : " -> " + shortId(request.assignee()))
				+ " fails=" + request.failures() + "/" + request.lifetimeFailures();
	}

	public static String shortId(UUID id) {
		return id.toString().substring(0, 8);
	}

	private static String format(String format, Object... args) {
		StringBuilder out = new StringBuilder();
		int arg = 0;
		for (int i = 0; i < format.length(); i++) {
			if (i + 1 < format.length() && format.charAt(i) == '{' && format.charAt(i + 1) == '}' && arg < args.length) {
				out.append(args[arg++]);
				i++;
			} else {
				out.append(format.charAt(i));
			}
		}
		return out.toString();
	}
}
