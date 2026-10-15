package dev.duzo.players.entities.possession;

import dev.duzo.players.Constants;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Possession is being rebuilt as a body swap; until then the link only binds. */
public final class PossessionManager {
	private PossessionManager() {}

	public static void tryStart(ServerPlayer player, ItemStack link) {
		Constants.traceEvent("possession denied {}: not built yet", player.getUUID());
		player.displayClientMessage(Component.literal("Possession is not available in this build").withStyle(ChatFormatting.RED), true);
	}

	public static void tick(MinecraftServer server) {}

	public static void onDisconnect(ServerPlayer player) {}

	public static void onJoin(ServerPlayer player) {}
}
