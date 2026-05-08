package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

public record ApplyFakePlayerSkinPacketC2S(int id, String name) {
	public static final Identifier LOCATION = PlayersCommon.id("apply_fake_player_skin");

	public static ApplyFakePlayerSkinPacketC2S decode(FriendlyByteBuf buf) {
		return new ApplyFakePlayerSkinPacketC2S(buf.readInt(), buf.readUtf());
	}

	public static void handle(PacketContext<ApplyFakePlayerSkinPacketC2S> ctx) {
		if (Side.SERVER.equals(ctx.side())) {
			try {
				if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) {
					Constants.LOG.error("Invalid entity id: {}", ctx.message().id);
					return;
				}
				String name = ctx.message().name();
				if (name.isEmpty()) return;
				entity.setSkin(name);
			} catch (Exception ignored) {
			}
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(name);
	}
}
