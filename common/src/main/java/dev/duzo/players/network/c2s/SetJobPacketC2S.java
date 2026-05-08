package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.Job;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SetJobPacketC2S(int id, int jobOrdinal) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("ai_set_job");

	public static SetJobPacketC2S decode(FriendlyByteBuf buf) {
		return new SetJobPacketC2S(buf.readInt(), buf.readInt());
	}

	public static void handle(PacketContext<SetJobPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		Job job = Job.byOrdinal(ctx.message().jobOrdinal());
		entity.mutateAIState(s -> {
			s.setJob(job);
			s.setRunning(false);
			s.setJobState(new CompoundTag());
		});
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeInt(jobOrdinal);
	}
}
