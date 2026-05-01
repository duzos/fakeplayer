package dev.duzo.players;

import dev.duzo.players.commands.SendChatCommand;
import dev.duzo.players.commands.SkinUrlCommand;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPItems;
import dev.duzo.players.network.PlayersNetwork;
import dev.duzo.players.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

public class PlayersCommon {
    public static void init() {
        PlayersConfig.load();

        FPItems.init();
        FPEntities.init();

        Services.COMMON_REGISTRY.registerCommand(SkinUrlCommand::register);
        Services.COMMON_REGISTRY.registerCommand(SendChatCommand::register);

        PlayersNetwork.init();
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(Constants.MOD_ID, path);
    }

    public static void tick(MinecraftServer server) {
    }
}