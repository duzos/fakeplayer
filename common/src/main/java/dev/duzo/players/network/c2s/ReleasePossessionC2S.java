package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.possession.PossessionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** The release key was pressed while possessing. Carries nothing: the server knows who is possessing, and ignores anyone else. */
public record ReleasePossessionC2S() {
	public static final ResourceLocation LOCATION = PlayersCommon.id("release_possession");

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
}
