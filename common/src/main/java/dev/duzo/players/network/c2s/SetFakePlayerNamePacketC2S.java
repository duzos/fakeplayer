package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public record SetFakePlayerNamePacketC2S(int id, String name) implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("set_fake_player_name");
	public static final CustomPacketPayload.Type<SetFakePlayerNamePacketC2S> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, SetFakePlayerNamePacketC2S> CODEC = CustomPacketPayload.codec(SetFakePlayerNamePacketC2S::encode, SetFakePlayerNamePacketC2S::decode);

	public static SetFakePlayerNamePacketC2S decode(FriendlyByteBuf buf) {
		return new SetFakePlayerNamePacketC2S(buf.readInt(), buf.readUtf());
	}

	public static void handle(PacketContext<SetFakePlayerNamePacketC2S> ctx) {
		if (Side.SERVER.equals(ctx.side())) {
			try {
				if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) {
					Constants.LOG.error("Invalid entity id: {}", ctx.message().id);
					return;
				}
				String name = ctx.message().name();
				entity.setNameWithoutSkin(name.isEmpty() ? null : Component.literal(name));
			} catch (Exception ignored) {
			}
		}
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(name);
	}
}
