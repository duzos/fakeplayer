package dev.duzo.players.platform.services;

import dev.duzo.players.entities.ai.Tackle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Reads mod-specific fishing tackle off a rod. Aquaculture has no Fabric build, so the only
 * implementation that knows about it is the Forge/NeoForge one; Fabric always reports
 * {@link Tackle#PLAIN}.
 */
public interface ITackleProvider {

	/** What this rod's fitted tackle does to a cast. Never null. */
	Tackle read(ItemStack rod);

	/** Consume or damage the bait fitted to this rod, after a catch that landed something. */
	void consumeBait(ServerLevel level, ItemStack rod);
}
