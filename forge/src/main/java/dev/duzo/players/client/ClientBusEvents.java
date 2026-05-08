package dev.duzo.players.client;

import dev.duzo.players.Constants;
import dev.duzo.players.client.renderers.SessionItemRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class ClientBusEvents {
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post e) {
		PlayersCommonClient.tick(Minecraft.getInstance());
	}

	@SubscribeEvent
	public static void onClientStopping(ClientPlayerNetworkEvent.LoggingOut e) {
		PlayersCommonClient.onClientStopping();
	}

	@SubscribeEvent
	public static void onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks e) {
		SessionItemRenderer.render(e.getPoseStack());
	}
}
