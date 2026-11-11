package dev.duzo.fppilot;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** `config/fppilot.properties`; `-Dfppilot.startEnabled=true` overrides startEnabled. */
public final class PilotConfig {
    private static final Properties PROPS = new Properties();

    private PilotConfig() {
    }

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("fppilot.properties");
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    PROPS.load(in);
                }
            } else {
                PROPS.setProperty("startEnabled", "false");
                PROPS.setProperty("port", "25599");
                Files.createDirectories(file.getParent());
                try (OutputStream out = Files.newOutputStream(file)) {
                    PROPS.store(out, "port is always bound to 127.0.0.1 only; startEnabled turns control on at launch");
                }
            }
        } catch (IOException e) {
            DevPilot.LOGGER.warn("[FPPilot] could not read {}", file, e);
        }
    }

    public static boolean startEnabled() {
        String override = System.getProperty("fppilot.startEnabled");
        if (override != null) return Boolean.parseBoolean(override);
        return Boolean.parseBoolean(PROPS.getProperty("startEnabled", "false").trim());
    }

    public static int port() {
        try {
            int port = Integer.parseInt(PROPS.getProperty("port", "25599").trim());
            return port >= 1024 && port <= 65535 ? port : 25599;
        } catch (NumberFormatException e) {
            return 25599;
        }
    }
}
