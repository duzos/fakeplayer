package dev.duzo.players;

import dev.duzo.players.platform.ForgeCommonRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class PlayersForge {
    public PlayersForge(IEventBus modEventBus) {
        PlayersCommon.init();

        ForgeCommonRegistry.init(modEventBus);
    }
}
