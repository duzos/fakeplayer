package dev.duzo.players.mixin;

import com.mojang.authlib.GameProfile;
import dev.duzo.players.client.PossessionDisguises;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every skin lookup for a player goes through here: the third-person model, the F5 view, the first-person arm and the tab
 * list head. A possessing player is drawn with the fake's skin by answering it here. The fake has no cape, so the player's own
 * cape and elytra texture are hidden for as long as they are disguised.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoSkinMixin {
	@Shadow
	public abstract GameProfile getProfile();

	@Inject(method = "getSkinLocation()Lnet/minecraft/resources/ResourceLocation;", at = @At("HEAD"), cancellable = true)
	private void players$disguiseSkin(CallbackInfoReturnable<ResourceLocation> cir) {
		PossessionDisguises.Skin skin = PossessionDisguises.skinFor(this.getProfile().getId());
		if (skin != null) cir.setReturnValue(skin.texture());
	}

	@Inject(method = "getModelName()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
	private void players$disguiseModel(CallbackInfoReturnable<String> cir) {
		PossessionDisguises.Skin skin = PossessionDisguises.skinFor(this.getProfile().getId());
		if (skin != null) cir.setReturnValue(skin.slim() ? "slim" : "default");
	}

	@Inject(method = "getCapeLocation()Lnet/minecraft/resources/ResourceLocation;", at = @At("HEAD"), cancellable = true)
	private void players$hideCape(CallbackInfoReturnable<ResourceLocation> cir) {
		if (PossessionDisguises.skinFor(this.getProfile().getId()) != null) cir.setReturnValue(null);
	}

	@Inject(method = "getElytraLocation()Lnet/minecraft/resources/ResourceLocation;", at = @At("HEAD"), cancellable = true)
	private void players$hideElytra(CallbackInfoReturnable<ResourceLocation> cir) {
		if (PossessionDisguises.skinFor(this.getProfile().getId()) != null) cir.setReturnValue(null);
	}
}
