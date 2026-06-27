package dev.duzo.players.client;

import dev.duzo.players.Constants;
import dev.duzo.players.client.renderers.FakeFishingHookRenderer;
import dev.duzo.players.client.renderers.FakePlayerRendererWrapper;
import dev.duzo.players.client.renderers.LegacyRodCast;
import dev.duzo.players.client.screen.FakeCrafterScreen;
import dev.duzo.players.client.screen.FakePlayerInventoryScreen;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {
	@SubscribeEvent
	public static void onEntityRenderersRegistry(EntityRenderersEvent.RegisterRenderers event) {
		event.registerEntityRenderer(FPEntities.FAKE_PLAYER.get(), FakePlayerRendererWrapper::new);
		event.registerEntityRenderer(FPEntities.FISHING_HOOK.get(), FakeFishingHookRenderer::new);
	}

	@SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			MenuScreens.register(FPMenus.FAKE_PLAYER.get(), FakePlayerInventoryScreen::new);
			MenuScreens.register(FPMenus.CRAFTER_LEARN.get(), FakeCrafterScreen::new);
			LegacyRodCast.register();
		});
	}
}