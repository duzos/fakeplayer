package dev.duzo.players;

import net.fabricmc.api.ModInitializer;

public class PlayersFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        PlayersCommon.init();
    }
}
