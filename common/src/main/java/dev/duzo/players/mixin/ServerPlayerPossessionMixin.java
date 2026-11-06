package dev.duzo.players.mixin;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.possession.PlayerStash;
import dev.duzo.players.entities.possession.PossessionManager;
import dev.duzo.players.entities.possession.PossessionStashHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import javax.annotation.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Everything a possessing player needs from the player class: the stash in their NBT, a death that returns them to their
 * body instead of ending, a game mode change that releases first, and the fake's name in the tab list.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerPossessionMixin implements PossessionStashHolder {
	@Unique
	private PlayerStash players$stash;
	@Unique private String players$saveToken = "";

	@Override public String players$getSaveToken() { return players$saveToken; }
	@Override public void players$setSaveToken(String token) { players$saveToken = token; }

	@Override
	public @Nullable PlayerStash players$getStash() {
		return this.players$stash;
	}

	@Override
	public void players$setStash(@Nullable PlayerStash stash) {
		this.players$stash = stash;
	}

	@Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
	private void players$writeStash(CompoundTag output, CallbackInfo ci) {
		if (this.players$stash != null) {
			RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, ((ServerPlayer) (Object) this).registryAccess());
			PlayerStash.CODEC.encodeStart(ops, this.players$stash)
					.resultOrPartial(error -> Constants.LOG.error("Could not encode a possession stash: {}", error))
					.ifPresent(tag -> output.put(PlayerStash.KEY, tag));
		}
		output.putString("players:possession_token", players$saveToken);
	}

	@Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
	private void players$readStash(CompoundTag input, CallbackInfo ci) {
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, ((ServerPlayer) (Object) this).registryAccess());
		Tag stashTag = input.get(PlayerStash.KEY);
		this.players$stash = stashTag == null ? null : PlayerStash.CODEC.parse(ops, stashTag).result().orElse(null);
		this.players$saveToken = input.getStringOr("players:possession_token", "");
	}

	@Inject(method = "restoreFrom(Lnet/minecraft/server/level/ServerPlayer;Z)V", at = @At("TAIL"))
	private void players$copyStash(ServerPlayer old, boolean alive, CallbackInfo ci) {
		this.players$saveToken = ((PossessionStashHolder) old).players$getSaveToken();
		PlayerStash stash = PossessionStashHolder.stashOf(old);
		if (stash == null) return;
		Constants.LOG.error("A possession stash survived into a respawn for {}; keeping it so login repair can still return the items", old.getUUID());
		Constants.traceEvent("possession stash carried over a respawn for {}", old.getUUID());
		this.players$stash = stash;
	}

	@Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"), cancellable = true)
	private void players$possessedDeath(DamageSource source, CallbackInfo ci) {
		if (PossessionManager.onPlayerDeath((ServerPlayer) (Object) this, source)) ci.cancel();
	}

	@Inject(method = "setGameMode(Lnet/minecraft/world/level/GameType;)Z", at = @At("HEAD"), cancellable = true)
	private void players$releaseBeforeGameMode(GameType mode, CallbackInfoReturnable<Boolean> cir) {
		if (!PossessionManager.beforeGameMode((ServerPlayer) (Object) this, mode)) cir.setReturnValue(false);
	}

	@Inject(method = "getTabListDisplayName()Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), cancellable = true)
	private void players$possessedTabName(CallbackInfoReturnable<Component> cir) {
		Component name = PossessionManager.tabName((ServerPlayer) (Object) this);
		if (name != null) cir.setReturnValue(name);
	}
}
