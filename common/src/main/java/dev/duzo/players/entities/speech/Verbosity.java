package dev.duzo.players.entities.speech;

/**
 * How much a fake is allowed to say. A line is spoken when its verbosity is at or below the
 * configured level, so ALWAYS lines are never muted.
 */
public enum Verbosity {
	/** Player-triggered lines (commands, a named paper). Always spoken. */
	ALWAYS,
	/** Problems the player has to act on: blocked, missing a tool, nothing fits. */
	NORMAL,
	/** Progress chatter: started, finished a stage, skipped a block. */
	CHATTY;

	/** Config values are "off" (ALWAYS only), "quiet" (plus problems) and "chatty" (everything). */
	public static Verbosity parseConfig(String value) {
		if ("off".equals(value)) return ALWAYS;
		if ("chatty".equals(value)) return CHATTY;
		return NORMAL;
	}

	public boolean allowedAt(Verbosity configured) {
		return this.ordinal() <= configured.ordinal();
	}
}
