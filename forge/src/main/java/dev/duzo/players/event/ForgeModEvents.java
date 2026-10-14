package dev.duzo.players.event;


import dev.duzo.players.Constants;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.RidingInteractions;
import dev.duzo.players.core.SessionItemSweeper;
import dev.duzo.players.entities.ai.FriendlyFire;
import dev.duzo.players.entities.possession.PossessionGuard;
import dev.duzo.players.entities.possession.PossessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
	public static void onAttack(LivingAttackEvent e) {
		if (FriendlyFire.shouldBlock(e.getSource(), e.getEntity())) e.setCanceled(true);
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

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
		if (PossessionGuard.blocks(e.getEntity())) {
			e.setCanceled(true);
			e.setCancellationResult(InteractionResult.FAIL);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
		if (PossessionGuard.blocks(e.getEntity())) {
			e.setCanceled(true);
			e.setCancellationResult(InteractionResult.FAIL);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
		if (PossessionGuard.blocks(e.getEntity())) {
			e.setCanceled(true);
			e.setCancellationResult(InteractionResult.FAIL);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific e) {
		if (PossessionGuard.blocks(e.getEntity())) {
			e.setCanceled(true);
			e.setCancellationResult(InteractionResult.FAIL);
		}
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
