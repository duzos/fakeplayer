package dev.duzo.players.entities.possession;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in fault boundaries for disposable development worlds. No property means no filesystem probes. */
final class PossessionFaults {
	private static final String DIRECTORY = System.getProperty("players.possessionFaultDir", "");
	private PossessionFaults() {}
	static boolean failWrite() { return enabled("fail-write"); }
	static boolean enabled(String name) { return !DIRECTORY.isEmpty() && Files.exists(Path.of(DIRECTORY, name)); }
	static void boundary(String name) {
		if (enabled(name)) Runtime.getRuntime().halt(86);
	}
}
