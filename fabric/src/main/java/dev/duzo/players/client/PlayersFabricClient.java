package dev.duzo.players.client;

import dev.duzo.players.client.preview.SchematicPreviewRenderer;
import dev.duzo.players.client.renderers.FakeFishingHookRenderer;
import dev.duzo.players.client.renderers.FakePlayerRendererWrapper;
import dev.duzo.players.client.renderers.PossessionBodyRenderer;
import dev.duzo.players.client.renderers.SessionItemRenderer;
import dev.duzo.players.client.screen.FakeCrafterScreen;
import dev.duzo.players.client.screen.FakePlayerInventoryScreen;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPMenus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

public class PlayersFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PlayersCommonClient.init();

		KeyMappingHelper.registerKeyMapping(FPKeybinds.OPEN_MENU);
		KeyMappingHelper.registerKeyMapping(FPKeybinds.RELEASE_POSSESSION);

		EntityRendererRegistry.register(FPEntities.FAKE_PLAYER.get(), FakePlayerRendererWrapper::new);
		EntityRendererRegistry.register(FPEntities.FISHING_HOOK.get(), FakeFishingHookRenderer::new);
		EntityRendererRegistry.register(FPEntities.POSSESSION_BODY.get(), PossessionBodyRenderer::new);
		PictureInPictureRendererRegistry.register(ctx -> new SchematicPreviewRenderer(ctx.bufferSource()));
		MenuScreens.register(FPMenus.FAKE_PLAYER.get(), FakePlayerInventoryScreen::new);
		MenuScreens.register(FPMenus.CRAFTER_LEARN.get(), FakeCrafterScreen::new);
		ClientTickEvents.END_CLIENT_TICK.register(PlayersCommonClient::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PlayersCommonClient.onClientStopping());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PlayersCommonClient.onDisconnect());
		// The fishing bobber/line are drawn by FakeFishingHookRenderer via the entity submit-node
		// pipeline; only the region/waypoint/chest gizmo overlay needs this world-render hook.
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ctx -> SessionItemRenderer.render(ctx.poseStack()));
	}
}
