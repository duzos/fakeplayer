package dev.duzo.players.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// remap=false: 26.x ships unobfuscated, so every target here is a mojmap name, same as FishingRodCastMixin
@Mixin(value = Player.class, remap = false)
public interface PlayerInvoker {
	@Invoker(value = "removeEntitiesOnShoulder", remap = false)
	void players$removeEntitiesOnShoulder();
}
