package dev.duzo.players.client;

import dev.duzo.players.Constants;
import dev.duzo.players.client.renderers.FakeFishingHookRenderer;
import dev.duzo.players.client.renderers.FakePlayerRendererWrapper;
import dev.duzo.players.client.screen.FakePlayerInventoryScreen;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {
	@SubscribeEvent
	public static void onEntityRenderersRegistry(EntityRenderersEvent.RegisterRenderers event) {
		event.registerEntityRenderer(FPEntities.FAKE_PLAYER.get(), FakePlayerRendererWrapper::new);
		event.registerEntityRenderer(FPEntities.FISHING_HOOK.get(), FakeFishingHookRenderer::new);
	}

	@SubscribeEvent
	public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
		event.register(FPMenus.FAKE_PLAYER.get(), FakePlayerInventoryScreen::new);
	}
}
