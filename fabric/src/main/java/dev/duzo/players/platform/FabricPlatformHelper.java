package dev.duzo.players.platform;

import dev.duzo.players.platform.services.IPlatformHelper;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import dev.duzo.players.entities.possession.PossessionGuard;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.loader.api.FabricLoader;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean canBreak(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, level.getBlockEntity(pos));
    }

    @Override
    public boolean canUse(ServerLevel level, ServerPlayer player, BlockHitResult hit) {
        if (!level.mayInteract(player, hit.getBlockPos())) return false;
        return PossessionGuard.queryBypassed(() ->
                UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit) != InteractionResult.FAIL);
    }

    @Override
    public boolean canPlace(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        return player.level().mayInteract(player, pos);
    }
}
