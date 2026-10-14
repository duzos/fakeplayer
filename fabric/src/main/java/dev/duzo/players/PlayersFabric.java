package dev.duzo.players;

import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.RidingInteractions;
import dev.duzo.players.core.SessionItemSweeper;
import dev.duzo.players.entities.possession.PossessionGuard;
import dev.duzo.players.entities.possession.PossessionManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

public class PlayersFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        PlayersCommon.init();

        ServerTickEvents.END_SERVER_TICK.register(SessionItemSweeper::tick);
        ServerTickEvents.END_SERVER_TICK.register(PossessionManager::tick);
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer p) {
                AIMarkerItem.clearAllFor(p);
                PossessionManager.onDeath(p);
            }
            return true;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            AIMarkerItem.clearAllFor(handler.player);
            PossessionManager.onDisconnect(handler.player);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> PossessionGuard.blocks(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> PossessionGuard.blocks(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) -> PossessionGuard.blocks(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> RidingInteractions.useEntity(player, world, hand, entity));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> RidingInteractions.useBlock(player, world, hand, hit));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> PossessionManager.onJoin(handler.player));
    }
}
