package dev.duzo.players.mixin;

import dev.duzo.players.entities.possession.PossessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla lets any spectator teleport to any entity with a packet. A possessing player is a spectator only so the camera
 * can follow the fake, and that teleport would drop the camera and leave them standing at the fake's side.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SpectatorTeleportMixin {
	@Redirect(method = "handleTeleportToEntityPacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
	private boolean players$notWhilePossessing(ServerPlayer player) {
		return !PossessionManager.isPossessing(player) && player.isSpectator();
	}
}
