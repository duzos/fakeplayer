package dev.duzo.players.client;

import dev.duzo.players.client.renderers.FakePlayerRendererWrapper;
import dev.duzo.players.client.renderers.SessionItemRenderer;
import dev.duzo.players.client.screen.FakePlayerInventoryScreen;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPMenus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.gui.screens.MenuScreens;

public class PlayersFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PlayersCommonClient.init();

		EntityRendererRegistry.register(FPEntities.FAKE_PLAYER.get(), FakePlayerRendererWrapper::new);
		MenuScreens.register(FPMenus.FAKE_PLAYER.get(), FakePlayerInventoryScreen::new);
		ClientTickEvents.END_CLIENT_TICK.register(PlayersCommonClient::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PlayersCommonClient.onClientStopping());
		WorldRenderEvents.AFTER_ENTITIES.register(ctx -> SessionItemRenderer.render(ctx.matrices()));
	}
}
