package dev.duzo.players.network.s2c;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.LocalSkinStore;
import dev.duzo.players.api.SkinGrabber;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

public record SkinDataPacketS2C(String key, byte[] data) {
	public static final Identifier LOCATION = PlayersCommon.id("skin_data");

	public static SkinDataPacketS2C decode(FriendlyByteBuf buf) {
		String key = buf.readUtf();
		byte[] data = buf.readByteArray(LocalSkinStore.MAX_BYTES + 1024);
		return new SkinDataPacketS2C(key, data);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeUtf(key);
		buf.writeByteArray(data);
	}

	public static void handle(PacketContext<SkinDataPacketS2C> ctx) {
		if (!Side.CLIENT.equals(ctx.side())) return;
		try {
			SkinGrabber.INSTANCE.acceptLocalSkin(ctx.message().key(), ctx.message().data());
		} catch (Exception e) {
			Constants.LOG.error("Failed to handle skin data packet", e);
		}
	}
}
