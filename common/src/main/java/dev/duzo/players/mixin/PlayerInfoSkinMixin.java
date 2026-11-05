package dev.duzo.players.mixin;

import com.mojang.authlib.GameProfile;
import dev.duzo.players.client.PossessionDisguises;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every skin lookup for a player goes through here: the third-person model, the F5 view, the first-person arm and the tab
 * list head. A possessing player is drawn with the fake's skin by answering it here.
 */
// remap=false: 26.x ships unobfuscated, so every target here is a mojmap name, same as FishingRodCastMixin
@Mixin(value = PlayerInfo.class, remap = false)
public abstract class PlayerInfoSkinMixin {
	@Shadow(remap = false)
	public abstract GameProfile getProfile();

	@Inject(method = "getSkin()Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("HEAD"), cancellable = true, remap = false)
	private void players$disguiseSkin(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin skin = PossessionDisguises.skinFor(this.getProfile().id());
		if (skin != null) cir.setReturnValue(skin);
	}
}
