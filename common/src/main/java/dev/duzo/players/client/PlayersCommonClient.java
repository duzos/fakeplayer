package dev.duzo.players.client;

import dev.duzo.players.api.SkinGrabber;
import dev.duzo.players.client.screen.SkinSelectScreen;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.Minecraft;

public class PlayersCommonClient {
	public static void init() {

	}

	public static void tick(Minecraft client) {
		SkinGrabber.INSTANCE.tick();
		FPKeybinds.tick(client);
	}

	public static void openSelectScreen(FakePlayerEntity entity) {
		Minecraft.getInstance().gui.setScreen(new SkinSelectScreen(entity));
	}

	/**
	 * Called when the client is stopping.
	 * Except on forge, because that doesnt exist as an event for some reason.
	 * On forge its called when the player logs out.
	 */
	public static void onClientStopping() {
		SkinGrabber.INSTANCE.onStopping();
	}
}
