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

	public static boolean onPlayerDeath(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source) {
		return false;
	}

	public static void beforeGameMode(ServerPlayer player, net.minecraft.world.level.GameType mode) {}

	@org.jspecify.annotations.Nullable
	public static Component tabName(ServerPlayer player) {
		return null;
	}

	public static void onBodyHurt(PossessionBodyEntity body, net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount) {}

	public static void checkBody(PossessionBodyEntity body, net.minecraft.server.level.ServerLevel level) {}

	public static void rebind(dev.duzo.players.entities.FakePlayerEntity fake, java.util.UUID possessor) {}
}
