package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record BondPacketC2S(int id, boolean bond) {
	public static final Identifier LOCATION = PlayersCommon.id("ai_bond");

	public static BondPacketC2S decode(FriendlyByteBuf buf) {
		return new BondPacketC2S(buf.readInt(), buf.readBoolean());
	}

	public static void handle(PacketContext<BondPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		if (!(sender.level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		UUID owner = entity.getAIState().ownerUUID();
		if (ctx.message().bond()) {
			// a bonded fake has to be unbonded by its owner first
			if (owner != null && !owner.equals(sender.getUUID())) return;
			entity.mutateAIState(s -> s.setOwnerUUID(sender.getUUID()));
			return;
		}
		if (owner == null || (!owner.equals(sender.getUUID()) && !AIMarkerItem.isOp(sender))) return;
		// needs the owner still on the fake, to find its requests
		dev.duzo.players.entities.ai.BuilderJobExecutor.cancelOutstanding(entity);
		entity.mutateAIState(s -> s.setOwnerUUID(null));
		dev.duzo.players.entities.ai.claims.WorkClaims.releaseAll(entity.getUUID());
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeBoolean(bond);
	}
}
