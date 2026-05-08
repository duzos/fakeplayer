package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public record BondPacketC2S(int id, boolean bond) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("ai_bond");

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

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeBoolean(bond);
	}
}
