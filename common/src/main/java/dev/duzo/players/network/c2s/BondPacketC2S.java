package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

public record BondPacketC2S(int id, boolean bond) implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("ai_bond");
	public static final CustomPacketPayload.Type<BondPacketC2S> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, BondPacketC2S> CODEC = CustomPacketPayload.codec(BondPacketC2S::encode, BondPacketC2S::decode);

	public static BondPacketC2S decode(FriendlyByteBuf buf) {
		return new BondPacketC2S(buf.readInt(), buf.readBoolean());
	}

	public static void handle(PacketContext<BondPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		if (!(sender.level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		entity.mutateAIState(s -> s.setOwnerUUID(ctx.message().bond() ? sender.getUUID() : null));
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeBoolean(bond);
	}
}
