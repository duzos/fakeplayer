package dev.duzo.players;

import dev.duzo.players.compat.TaczFabricCompat;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.RidingInteractions;
import dev.duzo.players.core.SessionItemSweeper;
import dev.duzo.players.entities.ai.FriendlyFire;
import dev.duzo.players.entities.possession.PossessionManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import dev.duzo.players.network.c2s.RequestSchematicPreviewC2S;
import net.minecraft.server.level.ServerPlayer;

public class PlayersFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        PlayersCommon.init();
        TaczFabricCompat.init();

        ServerTickEvents.END_SERVER_TICK.register(SessionItemSweeper::tick);
        ServerTickEvents.END_SERVER_TICK.register(PossessionManager::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !FriendlyFire.shouldBlock(source, entity));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer p) {
                AIMarkerItem.clearAllFor(p);
            }
            return true;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> RidingInteractions.useEntity(player, world, hand, entity));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> RidingInteractions.useBlock(player, world, hand, hit));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> PossessionManager.onJoin(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(PossessionManager::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PossessionManager.onServerStopped();
            RequestSchematicPreviewC2S.reset();
        });
    }
}
