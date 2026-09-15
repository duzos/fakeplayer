package dev.duzo.players.platform;

import dev.duzo.players.entities.ai.Tackle;
import dev.duzo.players.platform.services.ITackleProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** No tackle-aware fishing mod ships for Fabric, so every rod fishes plain water. */
public class FabricTackleProvider implements ITackleProvider {

	@Override
	public Tackle read(ItemStack rod) {
		return Tackle.PLAIN;
	}

	@Override
	public void consumeBait(ServerLevel level, ItemStack rod) {
	}
}
