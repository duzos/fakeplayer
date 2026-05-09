package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.GuardJobExecutor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

public record ClearPatrolPacketC2S(int id) {
	public static final Identifier LOCATION = PlayersCommon.id("ai_clear_patrol");

	public static ClearPatrolPacketC2S decode(FriendlyByteBuf buf) {
		return new ClearPatrolPacketC2S(buf.readInt());
	}

	public static void handle(PacketContext<ClearPatrolPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		entity.mutateAIState(GuardJobExecutor::clearPatrol);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
	}
}
