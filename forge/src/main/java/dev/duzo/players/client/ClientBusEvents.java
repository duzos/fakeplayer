package dev.duzo.players.client;

import dev.duzo.players.Constants;
import dev.duzo.players.client.renderers.AnchorHologram;
import dev.duzo.players.client.renderers.SessionItemRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class ClientBusEvents {
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post e) {
		PlayersCommonClient.tick(Minecraft.getInstance());
	}

	@SubscribeEvent
	public static void onClientStopping(ClientPlayerNetworkEvent.LoggingOut e) {
		PlayersCommonClient.onDisconnect();
		PlayersCommonClient.onClientStopping();
	}

	// AfterTranslucentBlocks only fires without improved transparency; this fires once a frame on both
	// paths. The fishing bobber/line are drawn by FakeFishingHookRenderer via the entity submit-node
	// pipeline, so only the session overlay and the anchor ghost need it.
	@SubscribeEvent
	public static void onSubmitGeometry(SubmitCustomGeometryEvent e) {
		SessionItemRenderer.render(e.getPoseStack());
		AnchorHologram.submitGhost(e.getPoseStack(), e.getSubmitNodeCollector());
	}
}
