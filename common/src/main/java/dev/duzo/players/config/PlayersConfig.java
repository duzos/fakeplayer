package dev.duzo.players.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.duzo.players.Constants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PlayersConfig {
	private static final Path PATH = Paths.get("config", "players.json");
	private static PlayersConfig INSTANCE;

	public String defaultSkin = "duzo";
	public double maxHealth = 25.0;
	public double movementSpeed = 0.2;
	public double attackDamage = 1.0;
	public boolean persistFakePlayers = true;
	public boolean allowLocalSkinUploadOpOnly = true;
	public double minerMaxBlocksPerSecond = 2.5;
	public int minerBailY = -58;
	public int guardRadius = 12;
	/** What the miner does with mined blocks past the build reserve: "ground", "chest", or "void". */
	public String minerSpoil = "ground";
	/** How far a fake may path in one search, clamped to 16-2048. Costs server tick time to raise: the pathfinder
	 * searches a cube of this radius and gets 16 nodes of budget per block of it. */
	public double pathRange = 256.0;
	/** How far a blocked fake looks for a Quartermaster, and a Quartermaster for a Runner. */
	public double requestRadius = 64.0;
	/** Ticks between pool index revalidations. Players and hoppers can touch pool chests, so the
	 * dirty flag alone is not enough to keep counts exact. */
	public int requestIndexInterval = 100;
	/** Cap on total requests one Quartermaster holds. The board shares a synced string capped at
	 * 32767 characters, so this cannot be raised without bound. */
	public int requestMaxPerQuartermaster = 64;
	/** Seconds before a shortfalled request is retried against the pool. */
	public int requestShortfallRetrySeconds = 15;

	public static PlayersConfig get() {
		if (INSTANCE == null) {
			load();
		}
		return INSTANCE;
	}

	private static final java.util.Set<String> MINER_SPOIL_VALUES = java.util.Set.of("ground", "chest", "void");

	public static void load() {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH)) {
					INSTANCE = gson.fromJson(r, PlayersConfig.class);
				}
				if (INSTANCE == null) {
					INSTANCE = new PlayersConfig();
				}
				validate();
				save(gson);
			} else {
				INSTANCE = new PlayersConfig();
				validate();
				save(gson);
			}
		} catch (IOException e) {
			Constants.LOG.error("Failed to load players.json, using defaults", e);
			INSTANCE = new PlayersConfig();
			validate();
		}
	}

	private static void validate() {
		if (INSTANCE.minerSpoil == null || !MINER_SPOIL_VALUES.contains(INSTANCE.minerSpoil)) {
			Constants.LOG.warn("players.json: minerSpoil '{}' is not one of {}, falling back to 'ground'",
					INSTANCE.minerSpoil, MINER_SPOIL_VALUES);
			INSTANCE.minerSpoil = "ground";
		}
		if (INSTANCE.requestRadius < 8.0 || INSTANCE.requestRadius > 256.0) {
			Constants.LOG.warn("players.json: requestRadius {} out of range 8-256, falling back to 64", INSTANCE.requestRadius);
			INSTANCE.requestRadius = 64.0;
		}
		if (INSTANCE.requestIndexInterval < 20) INSTANCE.requestIndexInterval = 20;
		if (INSTANCE.requestMaxPerQuartermaster < 1 || INSTANCE.requestMaxPerQuartermaster > 64) {
			Constants.LOG.warn("players.json: requestMaxPerQuartermaster {} out of range 1-64, falling back to 64",
					INSTANCE.requestMaxPerQuartermaster);
			INSTANCE.requestMaxPerQuartermaster = 64;
		}
		if (INSTANCE.requestShortfallRetrySeconds < 1) INSTANCE.requestShortfallRetrySeconds = 15;
	}

	private static void save(Gson gson) {
		try {
			Path parent = PATH.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (Writer w = Files.newBufferedWriter(PATH)) {
				gson.toJson(INSTANCE, w);
			}
		} catch (IOException e) {
			Constants.LOG.error("Failed to save players.json", e);
		}
	}
}
