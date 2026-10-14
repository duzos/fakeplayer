package dev.duzo.players;

import dev.duzo.players.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Constants {

	public static final String MOD_ID = "players";
	public static final String MOD_NAME = "Fake Players";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);
	private static boolean SILENCE_LOGS = true;

	/** Verbose state tracing for dev tests. Off unless started with -Dplayers.trace=true or /players trace on. */
	public static volatile boolean TRACE = Boolean.getBoolean("players.trace");
	private static final Map<String, String> LAST_TRACE = new ConcurrentHashMap<>();

	public static void debug(String format, Object... arguments) {
		if (!(Services.PLATFORM.isDevelopmentEnvironment()) || SILENCE_LOGS) return;

		LOG.info(format, arguments);
	}

	/**
	 * Trace a state line under {@code key} (usually a fake's UUID plus a topic). Repeats of the same
	 * line for the same key are dropped, so a fake stuck in one state logs once, not every tick.
	 */
	public static void trace(String key, String format, Object... arguments) {
		if (!TRACE) return;
		String line = MessageFormatter.arrayFormat(format, arguments).getMessage();
		if (line.equals(LAST_TRACE.put(key, line))) return;
		LOG.info("[fptrace] {}", line);
	}

	/** Trace a discrete event. Never deduplicated: two identical withdrawals are two withdrawals. */
	public static void traceEvent(String format, Object... arguments) {
		if (!TRACE) return;
		LOG.info("[fptrace] {}", MessageFormatter.arrayFormat(format, arguments).getMessage());
	}

	public static void setTrace(boolean on) {
		TRACE = on;
		LAST_TRACE.clear();
	}
}
