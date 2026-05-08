package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

public record ToggleFakePlayerFlagPacketC2S(int id, byte flag, boolean value) {
	public static final Identifier LOCATION = PlayersCommon.id("toggle_fake_player_flag");

	public static final byte FLAG_NO_AI = 0;
	public static final byte FLAG_SLIM = 1;
	public static final byte FLAG_NAMETAG = 2;

	public static ToggleFakePlayerFlagPacketC2S decode(FriendlyByteBuf buf) {
		return new ToggleFakePlayerFlagPacketC2S(buf.readInt(), buf.readByte(), buf.readBoolean());
	}

	public static void handle(PacketContext<ToggleFakePlayerFlagPacketC2S> ctx) {
		if (Side.SERVER.equals(ctx.side())) {
			try {
				if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) {
					Constants.LOG.error("Invalid entity id: {}", ctx.message().id);
					return;
				}
				switch (ctx.message().flag()) {
					case FLAG_NO_AI -> entity.setNoAi(ctx.message().value());
					case FLAG_SLIM -> entity.setSlim(ctx.message().value());
					case FLAG_NAMETAG -> entity.setCustomNameVisible(ctx.message().value());
					default -> Constants.LOG.error("Unknown flag: {}", ctx.message().flag());
				}
			} catch (Exception ignored) {
			}
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeByte(flag);
		buf.writeBoolean(value);
	}
}
