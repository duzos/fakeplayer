package dev.duzo.players.platform;

import dev.duzo.players.platform.services.IPlatformHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;

public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {

        return "Forge";
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
        return !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player));
    }

    @Override
    public boolean canPlace(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        BlockState against = level.getBlockState(pos.below());
        return !MinecraftForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snapshot, against, player));
    }
}
