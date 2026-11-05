package dev.duzo.players.network.s2c;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tells every client that a player is, or has stopped being, in a fake's body, and what that fake looks like. Clients draw
 * the player with that skin and name for as long as it lasts.
 */
public record PossessionDisguiseS2C(UUID player, boolean on, String skinName, String skinKey, String skinUrl, boolean slim, String fakeName) implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("possession_disguise");
	public static final CustomPacketPayload.Type<PossessionDisguiseS2C> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, PossessionDisguiseS2C> CODEC = CustomPacketPayload.codec(PossessionDisguiseS2C::encode, PossessionDisguiseS2C::decode);
	private static final int MAX_TEXT = 512;

	/** Filled in by the client entrypoint. A plain JDK type, so a dedicated server never loads a client class through this packet. */
	private static Consumer<PossessionDisguiseS2C> handler;

	public static void setHandler(Consumer<PossessionDisguiseS2C> value) {
		handler = value;
	}

	public static PossessionDisguiseS2C off(UUID player) {
		return new PossessionDisguiseS2C(player, false, "", "", "", false, "");
	}

	public static PossessionDisguiseS2C decode(FriendlyByteBuf buf) {
		return new PossessionDisguiseS2C(buf.readUUID(), buf.readBoolean(), buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT),
				buf.readBoolean(), buf.readUtf(MAX_TEXT));
	}

	public static void handle(PacketContext<PossessionDisguiseS2C> ctx) {
		if (!Side.CLIENT.equals(ctx.side()) || handler == null) return;
		handler.accept(ctx.message());
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeUUID(player);
		buf.writeBoolean(on);
		buf.writeUtf(skinName, MAX_TEXT);
		buf.writeUtf(skinKey, MAX_TEXT);
		buf.writeUtf(skinUrl, MAX_TEXT);
		buf.writeBoolean(slim);
		buf.writeUtf(fakeName, MAX_TEXT);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
