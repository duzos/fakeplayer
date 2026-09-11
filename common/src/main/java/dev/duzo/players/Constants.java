package dev.duzo.players;

import dev.duzo.players.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {

	public static final String MOD_ID = "players";
	public static final String MOD_NAME = "Fake Players";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);
	private static boolean SILENCE_LOGS = true;

	public static void debug(String format, Object... arguments) {
		if (!(Services.PLATFORM.isDevelopmentEnvironment()) || SILENCE_LOGS) return;

		LOG.info(format, arguments);
	}
}