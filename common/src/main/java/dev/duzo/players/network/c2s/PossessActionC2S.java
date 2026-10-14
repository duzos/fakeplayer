package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.possession.PossessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One click from a possessor, performed by the fake. Which fields matter depends on {@code action}:
 * 0 attack ({@code targetId}), 1 start breaking ({@code pos}), 2 stop breaking, 3 use on a block ({@code pos}, {@code face}
 * as a 3D data value and {@code hit} as the click offset inside the block, 0 to 1 per axis), 4 hotbar key ({@code slot}, 1 to 9).
 * Unused fields are sent as zero. Every field is validated on the server.
 */
public record PossessActionC2S(int fakeId, byte action, int targetId, BlockPos pos, byte face, float hx, float hy, float hz, byte slot) implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("possess_action");
	public static final CustomPacketPayload.Type<PossessActionC2S> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, PossessActionC2S> CODEC = CustomPacketPayload.codec(PossessActionC2S::encode, PossessActionC2S::decode);

	public static final byte ATTACK = 0;
	public static final byte BREAK_START = 1;
	public static final byte BREAK_STOP = 2;
	public static final byte USE = 3;
	public static final byte HOTBAR = 4;

	public static PossessActionC2S decode(FriendlyByteBuf buf) {
		return new PossessActionC2S(buf.readInt(), buf.readByte(), buf.readInt(), buf.readBlockPos(), buf.readByte(),
				buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readByte());
	}

	public static void handle(PacketContext<PossessActionC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side()) || ctx.sender() == null) return;
		PossessActionC2S m = ctx.message();
		PossessionManager.onAction(ctx.sender(), m.fakeId, m.action, m.targetId, m.pos, m.face, m.hx, m.hy, m.hz, m.slot);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(fakeId);
		buf.writeByte(action);
		buf.writeInt(targetId);
		buf.writeBlockPos(pos);
		buf.writeByte(face);
		buf.writeFloat(hx);
		buf.writeFloat(hy);
		buf.writeFloat(hz);
		buf.writeByte(slot);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
