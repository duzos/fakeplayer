package dev.duzo.players.client;

import dev.duzo.players.api.SkinGrabber;
import dev.duzo.players.client.screen.QuartermasterStockScreen;
import dev.duzo.players.client.screen.SkinSelectScreen;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.network.s2c.StockListPacketS2C;
import net.minecraft.client.Minecraft;

import java.util.List;

public class PlayersCommonClient {
	public static void init() {
		// registered from the client side so the packet class itself never names a Screen: a
		// dedicated server refuses to load client classes, and it loads every packet it registers
		StockListPacketS2C.setOpener(PlayersCommonClient::openStockScreen);
	}

	private static void openStockScreen(int entityId, List<StockListPacketS2C.Entry> stock, int total,
	                                    List<StockListPacketS2C.Pending> pending) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) return;
		if (!(minecraft.level.getEntity(entityId) instanceof FakePlayerEntity entity)) return;
		minecraft.setScreen(new QuartermasterStockScreen(entity, stock, total, pending));
	}

	public static void tick(Minecraft client) {
		SkinGrabber.INSTANCE.tick();
		FPKeybinds.tick(client);
	}

	public static void openSelectScreen(FakePlayerEntity entity) {
		Minecraft.getInstance().setScreen(new SkinSelectScreen(entity));
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
