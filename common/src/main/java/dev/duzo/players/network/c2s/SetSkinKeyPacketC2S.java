package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

public record SetSkinKeyPacketC2S(int id, String key, String url) {
	public static final Identifier LOCATION = PlayersCommon.id("set_skin_key");

	public static SetSkinKeyPacketC2S decode(FriendlyByteBuf buf) {
		return new SetSkinKeyPacketC2S(buf.readInt(), buf.readUtf(), buf.readUtf());
	}

	public static void handle(PacketContext<SetSkinKeyPacketC2S> ctx) {
		if (Side.SERVER.equals(ctx.side())) {
			try {
				if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) {
					Constants.LOG.error("Invalid entity id: {}", ctx.message().id);
					return;
				}

				entity.setSkin(entity.getSkinData().withKey(ctx.message().key()).withUrl(ctx.message().url()));
			} catch (Exception ignored) {
			}
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(key);
		buf.writeUtf(url);
	}
}
