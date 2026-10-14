package dev.duzo.players.event;


import dev.duzo.players.Constants;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.RidingInteractions;
import dev.duzo.players.core.SessionItemSweeper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import static dev.duzo.players.platform.ForgeCommonRegistry.COMMANDS;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID)
public class ForgeModEvents {
	@SubscribeEvent
	public static void registerCommands(RegisterCommandsEvent e) {
		COMMANDS.forEach(command -> command.accept(e.getDispatcher()));
	}

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent e) {
		if (e.phase != TickEvent.Phase.END) return;
		SessionItemSweeper.tick(e.getServer());
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onPlayerDeath(LivingDeathEvent e) {
		if (e.getEntity() instanceof ServerPlayer p) AIMarkerItem.clearAllFor(p);
	}

	@SubscribeEvent
	public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent e) {
		if (e.getEntity() instanceof ServerPlayer p) AIMarkerItem.clearAllFor(p);
	}

	@SubscribeEvent
	public static void onUseEntity(PlayerInteractEvent.EntityInteract e) {
		InteractionResult result = RidingInteractions.useEntity(e.getEntity(), e.getLevel(), e.getHand(), e.getTarget());
		if (result != InteractionResult.PASS) {
			e.setCancellationResult(result);
			e.setCanceled(true);
		}
	}

	@SubscribeEvent
	public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
		InteractionResult result = RidingInteractions.useBlock(e.getEntity(), e.getLevel(), e.getHand(), e.getHitVec());
		if (result != InteractionResult.PASS) {
			e.setCancellationResult(result);
			e.setCanceled(true);
		}
	}
}
