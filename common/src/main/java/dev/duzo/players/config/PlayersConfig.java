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

	public static PlayersConfig get() {
		if (INSTANCE == null) {
			load();
		}
		return INSTANCE;
	}

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
				save(gson);
			} else {
				INSTANCE = new PlayersConfig();
				save(gson);
			}
		} catch (IOException e) {
			Constants.LOG.error("Failed to load players.json, using defaults", e);
			INSTANCE = new PlayersConfig();
		}
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
