package dev.duzo.players.client;

import dev.duzo.players.Constants;
import dev.duzo.players.client.renderers.FakePlayerRendererWrapper;
import dev.duzo.players.core.FPEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class ClientModEvents {
	@SubscribeEvent
	public static void onEntityRenderersRegistry(EntityRenderersEvent.RegisterRenderers event) {
		event.registerEntityRenderer(FPEntities.FAKE_PLAYER.get(), FakePlayerRendererWrapper::new);
	}
}
