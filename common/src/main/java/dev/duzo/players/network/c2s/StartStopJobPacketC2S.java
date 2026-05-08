package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record StartStopJobPacketC2S(int id, boolean run) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("ai_start_stop");

	public static StartStopJobPacketC2S decode(FriendlyByteBuf buf) {
		return new StartStopJobPacketC2S(buf.readInt(), buf.readBoolean());
	}

	public static void handle(PacketContext<StartStopJobPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		if (!(ctx.sender().serverLevel().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		entity.mutateAIState(s -> s.setRunning(ctx.message().run()));
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeBoolean(run);
	}
}
