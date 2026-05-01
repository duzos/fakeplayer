package dev.duzo.players.client;

import dev.duzo.players.Constants;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.TickEvent;

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
}
