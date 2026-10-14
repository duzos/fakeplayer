package dev.duzo.players;

import dev.duzo.players.compat.TaczFabricCompat;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.SessionItemSweeper;
import dev.duzo.players.entities.ai.FriendlyFire;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

public class PlayersFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        PlayersCommon.init();
        TaczFabricCompat.init();

        ServerTickEvents.END_SERVER_TICK.register(SessionItemSweeper::tick);
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer p) AIMarkerItem.clearAllFor(p);
            return true;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !FriendlyFire.shouldBlock(source, entity));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                AIMarkerItem.clearAllFor(handler.player));
    }
}
