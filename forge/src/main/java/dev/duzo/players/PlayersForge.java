package dev.duzo.players;

import dev.duzo.players.platform.ForgeCommonRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;

@Mod(Constants.MOD_ID)
public class PlayersForge {
    public PlayersForge(IEventBus modEventBus) {
        PlayersCommon.init();

        ForgeCommonRegistry.init(modEventBus);

        if (FMLLoader.getDist() == Dist.CLIENT) {
            initClient(modEventBus);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void initClient(IEventBus modEventBus) {
    }
}
