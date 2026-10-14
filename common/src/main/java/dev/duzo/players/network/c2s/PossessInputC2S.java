package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.possession.PossessionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

/**
 * Sent every client tick while looking through a possessed fake: its entity id, where the mouse points, and the held
 * movement keys as {@code PossessionManager.FLAG_*} bits. The first one tells the server the client has the camera.
 */
public record PossessInputC2S(int fakeId, float yaw, float pitch, int flags) {
	public static final Identifier LOCATION = PlayersCommon.id("possess_input");

	public static PossessInputC2S decode(FriendlyByteBuf buf) {
		return new PossessInputC2S(buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readByte());
	}

	public static void handle(PacketContext<PossessInputC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side()) || ctx.sender() == null) return;
		PossessInputC2S msg = ctx.message();
		PossessionManager.onInput(ctx.sender(), msg.fakeId, msg.yaw, msg.pitch, msg.flags);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(fakeId);
		buf.writeFloat(yaw);
		buf.writeFloat(pitch);
		buf.writeByte(flags);
	}
}
