package dev.duzo.players.network.s2c;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.LocalSkinStore;
import dev.duzo.players.api.SkinGrabber;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SkinDataPacketS2C(String key, byte[] data) implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("skin_data");
	public static final CustomPacketPayload.Type<SkinDataPacketS2C> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, SkinDataPacketS2C> CODEC = CustomPacketPayload.codec(SkinDataPacketS2C::encode, SkinDataPacketS2C::decode);

	public static SkinDataPacketS2C decode(FriendlyByteBuf buf) {
		String key = buf.readUtf();
		byte[] data = buf.readByteArray(LocalSkinStore.MAX_BYTES + 1024);
		return new SkinDataPacketS2C(key, data);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
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
