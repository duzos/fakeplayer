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
	/** How far a blocked fake looks for a Quartermaster, and a Quartermaster for a Runner.
	 * 256 is 16 chunks in each direction. Raising it costs server tick time on a path that runs
	 * about once a second per waiting fake, and buys little past simulation distance, because only
	 * loaded entities are ever found. */
	public double requestRadius = 256.0;
	/** Config schema version, used only to migrate defaults that changed between releases. */
	public int configVersion = 0;
	/** Ticks between pool index revalidations. Players and hoppers can touch pool chests, so the
	 * dirty flag alone is not enough to keep counts exact. */
	public int requestIndexInterval = 100;
	/** Cap on total requests one Quartermaster holds. The board shares a synced string capped at
	 * 32767 characters, so this cannot be raised without bound. */
	public int requestMaxPerQuartermaster = 64;
	/** Seconds before a shortfalled request is retried against the pool. */
	public int requestShortfallRetrySeconds = 15;
	/** Seconds between keep-stocked checks on a fake that has a stock list. */
	public int requestStockCheckSeconds = 15;
	/** Seconds a commissioned craft may run before the Quartermaster gives up on it and asks again. */
	public int requestCraftTimeoutSeconds = 120;
	/** Largest schematic a Builder will load, in blocks of bounding volume (x * y * z). Plans hold every cell in
	 * memory, so this bounds the cost of one build. */
	public int builderMaxVolume = 32768;
	/** Width in blocks of the slab one Builder claims at a time, clamped to 1-16. Narrower stripes let more
	 * Builders share a build at the cost of more claim churn. */
	public int builderStripeWidth = 4;
	/** Ticks between two block placements by a Builder, minimum 1. */
	public int builderPlaceTicks = 4;
	/** Whether an installed Chunk Anchor keeps a fake's chunks loaded. False disables every anchor on the server
	 * without taking the items away; the recipe itself can be removed with a datapack. */
	public boolean chunkAnchors = true;
	/** How much fakes say: "off" (only lines a player asked for), "quiet" (plus problems they need to act on) or
	 * "chatty" (plus progress chatter). Each kind of line is spoken at most once per 30 seconds per fake, and a
	 * fake never speaks more than once per 2 seconds. */
	public String speechVerbosity = "quiet";
	/** True draws a fake's public lines as a speech bubble above its head. Every line also goes to chat as the fake's
	 * own {@code <name> message} line unless {@link #speechBubblesReplaceChat} is set. Alerts to one owner stay in chat. */
	public boolean speechBubbles = true;
	/** True keeps bubble-eligible lines out of chat while speechBubbles is on. Lines a player asked for (the chat
	 * command, a named paper) always reach chat. Has no effect while speechBubbles is off. */
	public boolean speechBubblesReplaceChat = false;
	/** Client side: how far away, in blocks, a speech bubble is still drawn. */
	public double bubbleRange = 24.0;
	/** Client side: most fakes that show a bubble at once; the nearest win. */
	public int bubbleMaxSpeakers = 4;
	/** Client side: most recent lines one bubble stacks. */
	public int bubbleMaxLines = 3;
	/** Client side: seconds a line stays up. */
	public double bubbleSeconds = 6.0;

	public static PlayersConfig get() {
		if (INSTANCE == null) {
			load();
		}
		return INSTANCE;
	}

	private static final java.util.Set<String> SPEECH_VERBOSITY_VALUES = java.util.Set.of("off", "quiet", "chatty");
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
		if (INSTANCE.speechVerbosity == null || !SPEECH_VERBOSITY_VALUES.contains(INSTANCE.speechVerbosity)) {
			Constants.LOG.warn("players.json: speechVerbosity '{}' is not one of {}, falling back to 'quiet'",
					INSTANCE.speechVerbosity, SPEECH_VERBOSITY_VALUES);
			INSTANCE.speechVerbosity = "quiet";
		}
		INSTANCE.bubbleRange = Math.max(4.0, Math.min(128.0, INSTANCE.bubbleRange));
		INSTANCE.bubbleMaxSpeakers = Math.max(1, Math.min(16, INSTANCE.bubbleMaxSpeakers));
		INSTANCE.bubbleMaxLines = Math.max(1, Math.min(8, INSTANCE.bubbleMaxLines));
		INSTANCE.bubbleSeconds = Math.max(1.0, Math.min(60.0, INSTANCE.bubbleSeconds));
		// 2.2.0 shipped requestRadius=64, which is smaller than a lot of real bases and made
		// requests fail silently. Move anyone still on that untouched default up once, then record
		// that it has been done so a deliberate 64 is never overwritten again.
		if (INSTANCE.configVersion < 1) {
			if (INSTANCE.requestRadius == 64.0) {
				Constants.LOG.info("players.json: migrating requestRadius from the old 64 default to 256");
				INSTANCE.requestRadius = 256.0;
			}
			INSTANCE.configVersion = 1;
		}
		if (INSTANCE.requestRadius < 8.0 || INSTANCE.requestRadius > 2048.0) {
			Constants.LOG.warn("players.json: requestRadius {} out of range 8-2048, falling back to 256", INSTANCE.requestRadius);
			INSTANCE.requestRadius = 256.0;
		}
		if (INSTANCE.requestIndexInterval < 20) INSTANCE.requestIndexInterval = 20;
		if (INSTANCE.requestMaxPerQuartermaster < 1 || INSTANCE.requestMaxPerQuartermaster > 64) {
			Constants.LOG.warn("players.json: requestMaxPerQuartermaster {} out of range 1-64, falling back to 64",
					INSTANCE.requestMaxPerQuartermaster);
			INSTANCE.requestMaxPerQuartermaster = 64;
		}
		if (INSTANCE.requestShortfallRetrySeconds < 1) INSTANCE.requestShortfallRetrySeconds = 15;
		if (INSTANCE.requestStockCheckSeconds < 1) INSTANCE.requestStockCheckSeconds = 15;
		if (INSTANCE.requestCraftTimeoutSeconds < 5) INSTANCE.requestCraftTimeoutSeconds = 120;
		if (INSTANCE.builderMaxVolume < 1) INSTANCE.builderMaxVolume = 32768;
		INSTANCE.builderStripeWidth = Math.max(1, Math.min(16, INSTANCE.builderStripeWidth));
		if (INSTANCE.builderPlaceTicks < 1) INSTANCE.builderPlaceTicks = 4;
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
