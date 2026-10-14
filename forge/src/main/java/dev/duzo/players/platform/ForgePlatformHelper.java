package dev.duzo.players.platform;

import dev.duzo.players.platform.services.IPlatformHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import dev.duzo.players.platform.services.UseCheck;
import net.minecraftforge.eventbus.api.Event;
import dev.duzo.players.entities.possession.PossessionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
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
        if (!level.mayInteract(player, pos)) return false;
        return !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player));
    }

    @Override
    public UseCheck canUse(ServerLevel level, ServerPlayer player, BlockHitResult hit) {
        if (!level.mayInteract(player, hit.getBlockPos())) return UseCheck.DENY;
        // listeners see the possessor's hand, not the fake's
        FakeLevelRightClick event = new FakeLevelRightClick(player, hit, level);
        PossessionGuard.queryBypassed(() -> {
            MinecraftForge.EVENT_BUS.post(event);
            return true;
        });
        if (event.isCanceled()) return event.getCancellationResult().consumesAction() ? UseCheck.HANDLED : UseCheck.DENY;
        return new UseCheck(UseCheck.Verdict.PROCEED, event.getUseBlock() != Event.Result.DENY, event.getUseItem() != Event.Result.DENY);
    }

    @Override
    public boolean canPlace(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        if (!level.mayInteract(player, pos)) return false;
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        BlockState against = level.getBlockState(pos.below());
        return !MinecraftForge.EVENT_BUS.post(new PlacingEvent(snapshot, against, player, state));
    }

    /** The event reads its placed block from the live world, which has not been built yet, so it reports the state about to be placed. */
    private static final class PlacingEvent extends BlockEvent.EntityPlaceEvent {
        private final BlockState placing;

        PlacingEvent(BlockSnapshot snapshot, BlockState against, ServerPlayer player, BlockState placing) {
            super(snapshot, against, player);
            this.placing = placing;
        }

        @Override
        public BlockState getPlacedBlock() {
            return placing;
        }

        @Override
        public BlockState getState() {
            return placing;
        }
    }

    /** A right click judged in the fake's level, since the possessor's own may be a different dimension. */
    private static final class FakeLevelRightClick extends PlayerInteractEvent.RightClickBlock {
        private final ServerLevel level;

        FakeLevelRightClick(ServerPlayer player, BlockHitResult hit, ServerLevel level) {
            super(player, InteractionHand.MAIN_HAND, hit.getBlockPos(), hit);
            this.level = level;
        }

        @Override
        public Level getLevel() {
            return level;
        }
    }
}
