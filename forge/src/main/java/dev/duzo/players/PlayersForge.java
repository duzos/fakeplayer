package dev.duzo.players;

import dev.duzo.players.compat.TaczForgeCompat;
import dev.duzo.players.platform.ForgeCommonRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;

@Mod(Constants.MOD_ID)
public class PlayersForge {
    public PlayersForge() {
        PlayersCommon.init();

        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ForgeCommonRegistry.init(bus);
        TaczForgeCompat.init();

        // check if this is for client
        if (FMLLoader.getDist() == Dist.CLIENT) {
            initClient();
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void initClient() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

    }
}