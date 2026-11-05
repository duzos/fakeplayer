package dev.duzo.players.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerList.class)
public interface PlayerListAccessor {
	/** Writes the player file now, rather than at the next autosave. */
	@Invoker("save")
	void players$save(ServerPlayer player);
}
