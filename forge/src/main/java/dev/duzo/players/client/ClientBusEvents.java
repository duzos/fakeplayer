package dev.duzo.players.client;

import dev.duzo.players.Constants;
import dev.duzo.players.client.render.SessionItemMarkerRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class ClientBusEvents {
	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent e) {
		PlayersCommonClient.tick(Minecraft.getInstance());
	}

	@SubscribeEvent
	public static void onClientStopping(ClientPlayerNetworkEvent.LoggingOut e) {
		PlayersCommonClient.onClientStopping();
	}

	@SubscribeEvent
	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
		Minecraft mc = Minecraft.getInstance();
		SessionItemMarkerRenderer.render(e.getPoseStack(),
				mc.renderBuffers().bufferSource(),
				e.getCamera().getPosition());
		mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
	}
}
