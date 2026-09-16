package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.JobType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SetJobPacketC2S(int id, ResourceLocation jobId) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("ai_set_job");

	public static SetJobPacketC2S decode(FriendlyByteBuf buf) {
		return new SetJobPacketC2S(buf.readInt(), ResourceLocation.tryParse(buf.readUtf()));
	}

	public static void handle(PacketContext<SetJobPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		if (!(ctx.sender().serverLevel().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		ResourceLocation jobId = ctx.message().jobId();
		// a client must not be able to set a job that does not exist, or to sneak past the
		// not-selectable marker jobs, whatever it sends
		JobType job = FPJobs.get(jobId);
		if (job == null || !job.selectable()) return;
		// let the old executor's pause path run and be persisted before its state is replaced below,
		// otherwise switching jobs mid-craft never gives it a chance to clean up.
		entity.resetJobExecutor();
		entity.mutateAIState(s -> {
			s.setJobId(jobId);
			s.setRunning(false);
			s.setJobState(new CompoundTag());
		});
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(jobId.toString());
	}
}
