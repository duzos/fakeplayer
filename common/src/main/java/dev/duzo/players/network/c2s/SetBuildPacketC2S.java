package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.builder.BuilderParams;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.UUID;

public record SetBuildPacketC2S(int id, String schematic, int rotation, int mirror, boolean clear) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("ai_set_build");

	public static SetBuildPacketC2S decode(FriendlyByteBuf buf) {
		return new SetBuildPacketC2S(buf.readInt(), buf.readUtf(BuilderParams.MAX_NAME), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
	}

	public static void handle(PacketContext<SetBuildPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		// a build spends the owner's materials and ground, so it is not something anyone else may set
		UUID owner = entity.getAIState().ownerUUID();
		if (owner == null || !owner.equals(ctx.sender().getUUID())) return;
		if (!FPJobs.is(entity.getAIState().jobId(), FPJobs.BUILDER)) return;

		BuilderParams old = BuilderParams.read(entity.getAIState());
		Rotation[] rotations = Rotation.values();
		Mirror[] mirrors = Mirror.values();
		BuilderParams next = new BuilderParams(ctx.message().schematic().trim(), old.anchor(),
				rotations[Math.floorMod(ctx.message().rotation(), rotations.length)],
				mirrors[Math.floorMod(ctx.message().mirror(), mirrors.length)], ctx.message().clear(), ctx.sender().getName().getString());
		if (next.equals(old)) return;
		next.write(entity);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(schematic, BuilderParams.MAX_NAME);
		buf.writeVarInt(rotation);
		buf.writeVarInt(mirror);
		buf.writeBoolean(clear);
	}
}
