package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.CustomBindTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells the server whether this client has bound the open-menu key. Sneak + right-click only opens the menu
 * for players who have not, so a bound key stops fighting mods that claim right-click on mobs.
 */
public record CustomBindStatePacketC2S(boolean bound) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("custom_bind_state");

	public static CustomBindStatePacketC2S decode(FriendlyByteBuf buf) {
		return new CustomBindStatePacketC2S(buf.readBoolean());
	}

	public static void handle(PacketContext<CustomBindStatePacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;

		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		CustomBindTracker.set(sender, ctx.message().bound);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeBoolean(bound);
	}
}
