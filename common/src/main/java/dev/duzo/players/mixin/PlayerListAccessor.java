package dev.duzo.players.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// remap=false: 26.x ships unobfuscated, so every target here is a mojmap name, same as FishingRodCastMixin
@Mixin(value = PlayerList.class, remap = false)
public interface PlayerListAccessor {
	/** Writes the player file now, rather than at the next autosave. */
	@Invoker(value = "save", remap = false)
	void players$save(ServerPlayer player);
}
