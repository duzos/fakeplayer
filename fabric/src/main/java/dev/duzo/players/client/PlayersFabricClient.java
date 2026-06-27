package dev.duzo.players.client;

import dev.duzo.players.client.render.SessionItemMarkerRenderer;
import dev.duzo.players.client.renderers.FakeFishingHookRenderer;
import dev.duzo.players.client.renderers.FakePlayerRendererWrapper;
import dev.duzo.players.client.renderers.FishingLineRenderer;
import dev.duzo.players.client.screen.FakeCrafterScreen;
import dev.duzo.players.client.screen.FakePlayerInventoryScreen;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPMenus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;

public class PlayersFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PlayersCommonClient.init();

		EntityRendererRegistry.register(FPEntities.FAKE_PLAYER.get(), FakePlayerRendererWrapper::new);
		EntityRendererRegistry.register(FPEntities.FISHING_HOOK.get(), FakeFishingHookRenderer::new);
		MenuScreens.register(FPMenus.FAKE_PLAYER.get(), FakePlayerInventoryScreen::new);
		MenuScreens.register(FPMenus.CRAFTER_LEARN.get(), FakeCrafterScreen::new);
		ClientTickEvents.END_CLIENT_TICK.register(PlayersCommonClient::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PlayersCommonClient.onClientStopping());
		WorldRenderEvents.LAST.register(ctx -> {
			SessionItemMarkerRenderer.render(ctx.matrixStack(),
					Minecraft.getInstance().renderBuffers().bufferSource(),
					ctx.camera().getPosition());
			Minecraft.getInstance().renderBuffers().bufferSource().endBatch(net.minecraft.client.renderer.RenderType.lines());
			FishingLineRenderer.render(ctx.matrixStack());
		});
	}
}
