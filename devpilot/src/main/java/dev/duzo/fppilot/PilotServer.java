package dev.duzo.fppilot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The control API. Bound to 127.0.0.1 only and gated by a random token that is regenerated every time
 * control is switched on, written with the port to `<game dir>/mbpilot/connection.json` for local tools.
 * Every request runs on the client thread, so it sees and changes state exactly as real input would.
 */
public final class PilotServer {
    private static final Logger LOGGER = DevPilot.LOGGER;
    private static final Gson GSON = new Gson();
    private static final long CALL_TIMEOUT_SECONDS = 10;

    private static HttpServer server;
    private static String token;

    private PilotServer() {
    }

    public static synchronized boolean isRunning() {
        return server != null;
    }

    public static synchronized int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public static synchronized void start(int port) throws IOException {
        if (server != null) return;
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        token = HexFormat.of().formatHex(bytes);

        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "FPPilot-HTTP");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/", PilotServer::handle);
        http.start();
        server = http;

        JsonObject connection = new JsonObject();
        connection.addProperty("host", "127.0.0.1");
        connection.addProperty("port", port);
        connection.addProperty("token", token);
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("mbpilot");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("connection.json"), GSON.toJson(connection), StandardCharsets.UTF_8);
        LOGGER.info("[FPPilot] remote control listening on 127.0.0.1:{}", port);
    }

    public static synchronized void stop() {
        if (server == null) return;
        server.stop(0);
        server = null;
        token = null;
        try {
            Files.deleteIfExists(Minecraft.getInstance().gameDirectory.toPath().resolve("mbpilot/connection.json"));
        } catch (IOException ignored) {
        }
        LOGGER.info("[FPPilot] remote control stopped");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorised(exchange)) {
                send(exchange, 401, error("missing or wrong token"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = query(exchange.getRequestURI());
            JsonObject body = body(exchange);
            String method = exchange.getRequestMethod();

            if (method.equals("GET") && path.equals("/screenshot")) {
                int scale = Integer.parseInt(query.getOrDefault("scale", "1"));
                byte[] png = screenshot(scale);
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(png);
                }
                return;
            }

            JsonElement result = switch (method + " " + path) {
                case "GET /status" -> onClient(StateReader::status);
                case "GET /hud" -> onClient(StateReader::hud);
                case "GET /screen" -> onClient(() -> StateReader.screen(flag(query, "tooltips", true)));
                case "GET /inventory" -> onClient(() -> StateReader.inventory(flag(query, "tooltips", false)));
                case "GET /entities" -> onClient(() -> StateReader.entities(
                        Double.parseDouble(query.getOrDefault("radius", "16")),
                        Integer.parseInt(query.getOrDefault("limit", "40"))));
                case "GET /target" -> onClient(StateReader::target);
                case "GET /chat" -> ChatLog.since(
                        Long.parseLong(query.getOrDefault("since", "0")),
                        Integer.parseInt(query.getOrDefault("limit", "100")));
                case "GET /keys" -> onClient(PilotServer::keys);
                case "POST /chat" -> onClient(() -> chat(body));
                case "POST /look" -> onClient(() -> look(body));
                case "POST /hold" -> onClient(() -> hold(body));
                case "POST /release" -> onClient(() -> release(body));
                case "POST /tap" -> onClient(() -> tap(string(body, "key")));
                case "POST /attack" -> onClient(() -> tap("key.attack"));
                case "POST /use" -> onClient(() -> tap("key.use"));
                case "POST /hotbar" -> onClient(() -> hotbar(body));
                case "POST /slot" -> onClient(() -> slot(body));
                case "POST /close" -> onClient(PilotServer::close);
                case "POST /widget" -> onClient(() -> widget(body));
                case "POST /mouse" -> onClient(() -> mouse(body));
                case "POST /type" -> onClient(() -> type(body));
                case "POST /keypress" -> onClient(() -> keypress(body));
                case "POST /connect" -> onClient(() -> connect(body));
                default -> null;
            };
            if (result == null) {
                send(exchange, 404, error("no route " + method + " " + path));
            } else {
                send(exchange, 200, result);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            send(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            LOGGER.warn("[FPPilot] request failed", e);
            send(exchange, 500, error(e.toString()));
        } finally {
            exchange.close();
        }
    }

    // ---- actions (client thread) ----

    private static JsonObject chat(JsonObject body) {
        LocalPlayer player = requirePlayer();
        String text = string(body, "text");
        if (text.startsWith("/")) player.connection.sendCommand(text.substring(1));
        else player.connection.sendChat(text);
        return ok();
    }

    private static JsonObject look(JsonObject body) {
        LocalPlayer player = requirePlayer();
        if (body.has("x")) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES,
                    new Vec3(body.get("x").getAsDouble(), body.get("y").getAsDouble(), body.get("z").getAsDouble()));
        } else {
            float yaw = body.has("yaw") ? body.get("yaw").getAsFloat() : player.getYRot();
            float pitch = body.has("pitch") ? body.get("pitch").getAsFloat() : player.getXRot();
            player.setYRot(yaw);
            player.setXRot(Math.max(-90f, Math.min(90f, pitch)));
            player.setYHeadRot(yaw);
        }
        JsonObject out = ok();
        out.addProperty("yaw", player.getYRot());
        out.addProperty("pitch", player.getXRot());
        return out;
    }

    /** {"key": "key.forward", "ticks": 20} or several at once: {"keys": {"key.forward": 20, "key.jump": 5}}. */
    private static JsonObject hold(JsonObject body) {
        if (body.has("keys")) {
            for (Map.Entry<String, JsonElement> entry : body.getAsJsonObject("keys").entrySet()) {
                Inputs.hold(requireMapping(entry.getKey()), entry.getValue().getAsInt());
            }
        } else {
            Inputs.hold(requireMapping(string(body, "key")), body.has("ticks") ? body.get("ticks").getAsInt() : 1);
        }
        return ok();
    }

    private static JsonObject release(JsonObject body) {
        if (body.has("key")) Inputs.release(requireMapping(string(body, "key")));
        else Inputs.releaseAll();
        return ok();
    }

    private static JsonObject tap(String key) {
        Inputs.click(requireMapping(key));
        return ok();
    }

    private static JsonObject hotbar(JsonObject body) {
        int slot = body.get("slot").getAsInt();
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("hotbar slot must be 0-8");
        requirePlayer().getInventory().setSelectedSlot(slot);
        return ok();
    }

    /** Clicks a slot of the open container screen: {"slot": 13, "button": 0, "type": "PICKUP"}. */
    private static JsonObject slot(JsonObject body) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = requirePlayer();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            throw new IllegalStateException("no container screen is open");
        }
        int slot = body.get("slot").getAsInt();
        int button = body.has("button") ? body.get("button").getAsInt() : 0;
        ContainerInput type = ContainerInput.valueOf(body.has("type") ? body.get("type").getAsString() : "PICKUP");
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, button, type, player);
        return ok();
    }

    private static JsonObject close() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
        else mc.gui.setScreen(null);
        return ok();
    }

    private static Screen requireScreen() {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null) throw new IllegalStateException("no screen is open");
        return screen;
    }

    /** Clicks a widget of the open screen, by {"index"} (from GET /screen) or by its {"text"}. */
    private static JsonObject widget(JsonObject body) {
        Screen screen = requireScreen();
        var children = screen.children();
        AbstractWidget target = null;
        if (body.has("index")) {
            int index = body.get("index").getAsInt();
            if (index >= 0 && index < children.size() && children.get(index) instanceof AbstractWidget w) target = w;
        } else {
            String text = string(body, "text");
            for (var child : children) {
                if (child instanceof AbstractWidget w && w.getMessage().getString().equalsIgnoreCase(text)) {
                    target = w;
                    break;
                }
            }
        }
        if (target == null) throw new IllegalArgumentException("no such widget (GET /screen lists them)");
        if (!target.isActive()) throw new IllegalStateException("widget '" + target.getMessage().getString() + "' is disabled");
        click(screen, target.getX() + target.getWidth() / 2.0, target.getY() + target.getHeight() / 2.0, 0, modifiers(body));
        return ok();
    }

    /** A click at screen (GUI-scaled) coordinates: {"x", "y", "button": 0, "modifiers": 0}. */
    private static JsonObject mouse(JsonObject body) {
        click(requireScreen(), body.get("x").getAsDouble(), body.get("y").getAsDouble(),
                body.has("button") ? body.get("button").getAsInt() : 0, modifiers(body));
        return ok();
    }

    /**
     * The API takes GLFW-style modifier bits (1 shift, 2 control, 4 alt, 8 super), as it does on every other branch.
     * 26.x reads SDL modifier masks, so they are translated here. For shift-click and the like.
     */
    private static int modifiers(JsonObject body) {
        int glfw = body.has("modifiers") ? body.get("modifiers").getAsInt() : 0;
        int sdl = 0;
        if ((glfw & 1) != 0) sdl |= InputConstants.MOD_SHIFT;
        if ((glfw & 2) != 0) sdl |= InputConstants.MOD_CONTROL;
        if ((glfw & 4) != 0) sdl |= InputConstants.MOD_ALT;
        if ((glfw & 8) != 0) sdl |= InputConstants.MOD_SUPER;
        return sdl;
    }

    private static void click(Screen screen, double x, double y, int button, int modifiers) {
        // the API keeps GLFW numbering (0 left, 1 right, 2 middle); 26.3 input is SDL, where left is 1
        int sdlButton = switch (button) {
            case 0 -> InputConstants.MOUSE_BUTTON_LEFT;
            case 1 -> InputConstants.MOUSE_BUTTON_RIGHT;
            case 2 -> InputConstants.MOUSE_BUTTON_MIDDLE;
            default -> button;
        };
        MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(sdlButton, modifiers));
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }

    /** Types into the focused widget of the open screen, as character input. */
    private static JsonObject type(JsonObject body) {
        Screen screen = requireScreen();
        string(body, "text").codePoints().forEach(c -> screen.charTyped(new CharacterEvent(c)));
        return ok();
    }

    private static final Map<String, Integer> NAMED_KEYS = Map.of(
            "ENTER", InputConstants.KEY_RETURN, "ESCAPE", InputConstants.KEY_ESCAPE, "TAB", InputConstants.KEY_TAB,
            "BACKSPACE", InputConstants.KEY_BACKSPACE, "UP", InputConstants.KEY_UP, "DOWN", InputConstants.KEY_DOWN,
            "LEFT", InputConstants.KEY_LEFT, "RIGHT", InputConstants.KEY_RIGHT, "DELETE", InputConstants.KEY_DELETE);

    /** A key press on the open screen: {"key": "ENTER"} (ENTER, ESCAPE, TAB, ...) or an SDL scancode. */
    private static JsonObject keypress(JsonObject body) {
        Screen screen = requireScreen();
        String key = string(body, "key").toUpperCase(java.util.Locale.ROOT);
        Integer code = NAMED_KEYS.get(key);
        if (code == null) {
            try {
                code = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("unknown key '" + key + "'; use one of " + NAMED_KEYS.keySet() + " or an SDL scancode");
            }
        }
        KeyEvent event = new KeyEvent(code, 0, 0);
        screen.keyPressed(event);
        screen.keyReleased(event);
        return ok();
    }

    /** Joins a server: {"address": "play.example.com"}. */
    private static JsonObject connect(JsonObject body) {
        Minecraft mc = Minecraft.getInstance();
        String address = string(body, "address");
        if (mc.level != null) throw new IllegalStateException("already in a world; disconnect first");
        ConnectScreen.startConnecting(mc.gui.screen() == null ? new TitleScreen() : mc.gui.screen(), mc,
                ServerAddress.parseString(address), new ServerData(address, address, ServerData.Type.OTHER), false, null);
        return ok();
    }

    private static JsonObject keys() {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            JsonObject k = new JsonObject();
            k.addProperty("name", mapping.getName());
            k.addProperty("bound", KeyMappingHelper.getBoundKeyOf(mapping).getName());
            k.addProperty("down", mapping.isDown());
            list.add(k);
        }
        out.add("keys", list);
        return out;
    }

    private static byte[] screenshot(int scale) throws Exception {
        CompletableFuture<byte[]> png = new CompletableFuture<>();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), Math.max(1, scale), image -> {
            try {
                Path file = Files.createTempFile("mbpilot", ".png");
                image.writeToFile(file);
                png.complete(Files.readAllBytes(file));
                Files.deleteIfExists(file);
            } catch (Throwable t) {
                png.completeExceptionally(t);
            } finally {
                image.close();
            }
        }));
        return png.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // ---- plumbing ----

    private static <T extends JsonElement> T onClient(Supplier<T> action) throws Exception {
        try {
            return Minecraft.getInstance().submit(action).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // Surface the action's own failure, so a bad request reads as a 400 rather than a 500.
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static LocalPlayer requirePlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("not in a world");
        return player;
    }

    private static KeyMapping requireMapping(String name) {
        KeyMapping mapping = Inputs.mapping(name);
        if (mapping == null) throw new IllegalArgumentException("no key mapping '" + name + "' (GET /keys lists them)");
        return mapping;
    }

    private static boolean authorised(HttpExchange exchange) {
        String current;
        synchronized (PilotServer.class) {
            current = token;
        }
        if (current == null) return false;
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.equals("Bearer " + current)) return true;
        return current.equals(query(exchange.getRequestURI()).get("token"));
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return new JsonObject();
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("body must be a JSON object");
            return parsed.getAsJsonObject();
        }
    }

    private static boolean flag(Map<String, String> query, String name, boolean fallback) {
        String value = query.get(name);
        return value == null ? fallback : value.equals("1") || value.equalsIgnoreCase("true");
    }

    private static String string(JsonObject body, String name) {
        if (!body.has(name)) throw new IllegalArgumentException("missing '" + name + "'");
        return body.get(name).getAsString();
    }

    private static JsonObject ok() {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        return out;
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("error", message);
        return out;
    }

    private static void send(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
