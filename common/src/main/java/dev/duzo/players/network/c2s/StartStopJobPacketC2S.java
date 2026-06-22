package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

public record StartStopJobPacketC2S(int id, boolean run) {
	public static final Identifier LOCATION = PlayersCommon.id("ai_start_stop");

	public static StartStopJobPacketC2S decode(FriendlyByteBuf buf) {
		return new StartStopJobPacketC2S(buf.readInt(), buf.readBoolean());
	}

	public static void handle(PacketContext<StartStopJobPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		entity.flushJobState();
		entity.mutateAIState(s -> {
			s.setRunning(ctx.message().run());
			if (ctx.message().run()) {
				s.setJobState(restartableJobState(s.jobState()));
			}
		});
		entity.resetJobExecutor();
	}

	private static CompoundTag restartableJobState(CompoundTag state) {
		CompoundTag copy = state == null ? new CompoundTag() : state.copy();
		copy.putBoolean("Bailed", false);
		copy.putInt("PathFail", 0);
		copy.remove("ActiveTarget");
		copy.remove("ActiveStand");
		copy.remove("MiningProgress");
		copy.remove("MiningStage");
		return copy;
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeBoolean(run);
	}
}
