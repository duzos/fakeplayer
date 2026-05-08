package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.FakePlayerEntity.PhysicalState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record CyclePosePacketC2S(int id) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("cycle_pose");

	public static CyclePosePacketC2S decode(FriendlyByteBuf buf) {
		return new CyclePosePacketC2S(buf.readInt());
	}

	public static void handle(PacketContext<CyclePosePacketC2S> ctx) {
		if (Side.SERVER.equals(ctx.side())) {
			try {
				if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) {
					Constants.LOG.error("Invalid entity id: {}", ctx.message().id);
					return;
				}
				PhysicalState[] all = PhysicalState.values();
				entity.setPhysicalState(all[(entity.getPhysicalState().ordinal() + 1) % all.length]);
			} catch (Exception ignored) {
			}
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
	}
}
