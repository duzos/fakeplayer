package dev.duzo.fppilot;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only remote control for your own client. Off until toggled (F9 by default, or
 * `startEnabled` in the config); while on, a localhost API ({@link PilotServer}) can read game state
 * and drive input, and a marker stays on screen so it is never on without you knowing.
 */
public final class DevPilot implements ClientModInitializer {
    public static final String MOD_ID = "fppilot";
    public static final Logger LOGGER = LoggerFactory.getLogger("fppilot");

    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
    private static KeyMapping toggle;

    /** pauseOnLostFocus as it was before control went on; null while off. */
    private static Boolean savedPauseOnLostFocus;
    private static boolean autoStartChecked;

    @Override
    public void onInitializeClient() {
        PilotConfig.load();
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fppilot.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> ChatLog.add("chat", message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> ChatLog.add(overlay ? "actionbar" : "system", message));
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath(MOD_ID, "marker"), (graphics, tickCounter) -> {
                    if (!PilotServer.isRunning()) return;
                    graphics.text(Minecraft.getInstance().font, "● MBPilot", 4, 4, 0xFFFF5555);
                });
    }

    private static void onTick() {
        // a request served during the startup resource reload kills the 26.3 client natively, so wait it out
        if (!autoStartChecked && Minecraft.getInstance().isGameLoadFinished()) {
            autoStartChecked = true;
            if (PilotConfig.startEnabled()) setEnabled(true);
        }
        while (toggle.consumeClick()) setEnabled(!PilotServer.isRunning());
        if (PilotServer.isRunning()) {
            // Driven from outside, the window rarely has focus; the pause menu would swallow movement.
            Minecraft.getInstance().options.pauseOnLostFocus = false;
        }
        Inputs.tick();
    }

    public static void setEnabled(boolean enabled) {
        Minecraft mc = Minecraft.getInstance();
        if (enabled) {
            try {
                PilotServer.start(PilotConfig.port());
                if (savedPauseOnLostFocus == null) savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
                notify("Remote control ON (127.0.0.1:" + PilotServer.port() + ")");
            } catch (Exception e) {
                LOGGER.error("[FPPilot] could not start", e);
                notify("Remote control failed to start: " + e.getMessage());
            }
        } else {
            PilotServer.stop();
            Inputs.releaseAll();
            if (savedPauseOnLostFocus != null) {
                mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
                savedPauseOnLostFocus = null;
            }
            notify("Remote control OFF");
        }
    }

    private static void notify(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("[FPPilot] " + message));
        LOGGER.info("[FPPilot] {}", message);
    }
}
