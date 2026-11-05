package dev.duzo.players.platform;

import dev.duzo.players.platform.services.IPlatformHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {

        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public String getModVersion(String modId) {

        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString()).orElse(null);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return !FMLLoader.isProduction();
    }

    @Override
    public boolean canBreak(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (!level.mayInteract(player, pos)) return false;
        return !NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player)).isCanceled();
    }
}
