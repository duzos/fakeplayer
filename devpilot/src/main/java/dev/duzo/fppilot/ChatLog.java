package dev.duzo.fppilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Everything the client was shown in chat, the action bar included, numbered so a reader can ask for
 * "everything after id N" and never miss or repeat a line.
 */
public final class ChatLog {
    private static final int CAPACITY = 1000;
    private static final Deque<JsonObject> LINES = new ArrayDeque<>();
    private static long nextId = 1;

    private ChatLog() {
    }

    public static synchronized void add(String kind, Component message) {
        JsonObject line = new JsonObject();
        line.addProperty("id", nextId++);
        line.addProperty("time", System.currentTimeMillis());
        line.addProperty("kind", kind);
        line.addProperty("text", message.getString());
        LINES.addLast(line);
        while (LINES.size() > CAPACITY) LINES.removeFirst();
    }

    /** Lines with an id above [since], oldest first, at most [limit] of the newest. */
    public static synchronized JsonObject since(long since, int limit) {
        JsonArray out = new JsonArray();
        LINES.stream().filter(l -> l.get("id").getAsLong() > since)
                .skip(Math.max(0, LINES.stream().filter(l -> l.get("id").getAsLong() > since).count() - limit))
                .forEach(out::add);
        JsonObject result = new JsonObject();
        result.addProperty("last", nextId - 1);
        result.add("lines", out);
        return result;
    }
}
