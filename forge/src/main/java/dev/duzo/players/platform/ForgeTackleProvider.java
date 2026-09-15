package dev.duzo.players.platform;

import dev.duzo.players.compat.AquacultureCompat;
import dev.duzo.players.entities.ai.Tackle;
import dev.duzo.players.platform.services.ITackleProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public class ForgeTackleProvider implements ITackleProvider {

	@Override
	public Tackle read(ItemStack rod) {
		return AquacultureCompat.isPresent() ? AquacultureCompat.read(rod) : Tackle.PLAIN;
	}

	@Override
	public void consumeBait(ServerLevel level, ItemStack rod) {
		if (AquacultureCompat.isPresent()) AquacultureCompat.consumeBait(level, rod);
	}
}
