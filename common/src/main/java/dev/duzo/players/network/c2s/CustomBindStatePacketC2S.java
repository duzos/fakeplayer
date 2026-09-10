package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.CustomBindTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells the server whether this client has bound the open-menu key. Sneak + right-click only opens the menu
 * for players who have not, so a bound key stops fighting mods that claim right-click on mobs.
 */
public record CustomBindStatePacketC2S(boolean bound) implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("custom_bind_state");
	public static final CustomPacketPayload.Type<CustomBindStatePacketC2S> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, CustomBindStatePacketC2S> CODEC = CustomPacketPayload.codec(CustomBindStatePacketC2S::encode, CustomBindStatePacketC2S::decode);

	public static CustomBindStatePacketC2S decode(FriendlyByteBuf buf) {
		return new CustomBindStatePacketC2S(buf.readBoolean());
	}

	public static void handle(PacketContext<CustomBindStatePacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;

		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		CustomBindTracker.set(sender, ctx.message().bound);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeBoolean(bound);
	}
}
