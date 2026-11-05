package dev.duzo.players.mixin;

import dev.duzo.players.entities.possession.PossessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server-thread removal, before vanilla captures the player's level and saves their inventory. */
// remap=false: 26.x ships unobfuscated, so every target here is a mojmap name, same as FishingRodCastMixin
@Mixin(value = PlayerList.class, remap = false)
public abstract class PlayerListPossessionMixin {
	@Inject(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), remap = false)
	private void players$releaseBeforeRemoval(ServerPlayer player, CallbackInfo ci) {
		PossessionManager.onDisconnect(player);
	}
}
