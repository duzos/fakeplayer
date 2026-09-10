package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.menu.FakePlayerMenuProvider;
import dev.duzo.players.platform.Services;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Sent when the player presses the open-menu keybind while looking at a fake. */
public record OpenFakeMenuPacketC2S(int id) {
	public static final Identifier LOCATION = PlayersCommon.id("open_fake_menu");
	/** Squared reach the server will honour, a little past the vanilla 4.5 block limit to allow for latency. */
	private static final double REACH_SQR = 36.0;

	public static OpenFakeMenuPacketC2S decode(FriendlyByteBuf buf) {
		return new OpenFakeMenuPacketC2S(buf.readInt());
	}

	public static void handle(PacketContext<OpenFakeMenuPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;

		ServerPlayer sender = ctx.sender();
		if (sender == null || sender.isSpectator()) return;
		if (!(sender.level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		if (entity.distanceToSqr(sender) > REACH_SQR) return;

		Services.COMMON_REGISTRY.openMenu(sender, new FakePlayerMenuProvider(entity), buf -> buf.writeInt(entity.getId()));
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
	}
}
