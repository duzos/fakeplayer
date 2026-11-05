package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.possession.PossessionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** The release key was pressed while possessing. Carries nothing: the server knows who is possessing, and ignores anyone else. */
public record ReleasePossessionC2S() implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("release_possession");
	public static final CustomPacketPayload.Type<ReleasePossessionC2S> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, ReleasePossessionC2S> CODEC = CustomPacketPayload.codec(ReleasePossessionC2S::encode, ReleasePossessionC2S::decode);

	public static ReleasePossessionC2S decode(FriendlyByteBuf buf) {
		return new ReleasePossessionC2S();
	}

	public static void handle(PacketContext<ReleasePossessionC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		PossessionManager.release(sender, PossessionManager.Reason.RELEASED, null);
	}

	public void encode(FriendlyByteBuf buf) {}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
