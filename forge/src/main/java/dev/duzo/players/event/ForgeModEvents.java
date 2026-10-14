package dev.duzo.players.event;


import dev.duzo.players.Constants;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.SessionItemSweeper;
import dev.duzo.players.entities.possession.PossessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import static dev.duzo.players.platform.ForgeCommonRegistry.COMMANDS;

@EventBusSubscriber(modid = Constants.MOD_ID)
public class ForgeModEvents {
	@SubscribeEvent
	public static void registerCommands(RegisterCommandsEvent e) {
		COMMANDS.forEach(command -> command.accept(e.getDispatcher()));
	}

	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post e) {
		SessionItemSweeper.tick(e.getServer());
		PossessionManager.tick(e.getServer());
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onPlayerDeath(LivingDeathEvent e) {
		if (e.getEntity() instanceof ServerPlayer p) {
			AIMarkerItem.clearAllFor(p);
			PossessionManager.onDeath(p);
		}
	}

	@SubscribeEvent
	public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent e) {
		if (e.getEntity() instanceof ServerPlayer p) {
			AIMarkerItem.clearAllFor(p);
			PossessionManager.onDisconnect(p);
		}
	}

	@SubscribeEvent
	public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent e) {
		if (e.getEntity() instanceof ServerPlayer p) PossessionManager.onJoin(p);
	}
}
