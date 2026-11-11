package dev.duzo.fppilot;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Synthetic input. Holds press a key mapping for a number of ticks the way a held physical key would;
 * clicks queue one press, exactly what a single tap of the bound key does. Client thread only.
 */
public final class Inputs {
    private static final Map<KeyMapping, Integer> HELD = new HashMap<>();

    private Inputs() {
    }

    /** The key mapping called [name] (`key.forward`, `key.jump`, `key.sneak`, ...), or null. */
    public static KeyMapping mapping(String name) {
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if (mapping.getName().equals(name)) return mapping;
        }
        return null;
    }

    public static void hold(KeyMapping mapping, int ticks) {
        if (ticks <= 0) {
            release(mapping);
            return;
        }
        mapping.setDown(true);
        HELD.put(mapping, ticks);
    }

    public static void release(KeyMapping mapping) {
        HELD.remove(mapping);
        mapping.setDown(false);
    }

    public static void click(KeyMapping mapping) {
        KeyMapping.click(KeyMappingHelper.getBoundKeyOf(mapping));
    }

    public static void releaseAll() {
        HELD.keySet().forEach(mapping -> mapping.setDown(false));
        HELD.clear();
    }

    public static void tick() {
        Iterator<Map.Entry<KeyMapping, Integer>> iterator = HELD.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<KeyMapping, Integer> entry = iterator.next();
            int left = entry.getValue() - 1;
            if (left <= 0) {
                entry.getKey().setDown(false);
                iterator.remove();
            } else {
                // Re-assert: focus changes reset every mapping's state.
                entry.getKey().setDown(true);
                entry.setValue(left);
            }
        }
    }

    public static Map<String, Integer> held() {
        Map<String, Integer> out = new HashMap<>();
        HELD.forEach((mapping, ticks) -> out.put(mapping.getName(), ticks));
        return out;
    }
}
