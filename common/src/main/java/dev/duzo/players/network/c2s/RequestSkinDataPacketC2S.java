package dev.duzo.players.network.c2s;

import commonnetwork.api.Network;
import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.LocalSkinStore;
import dev.duzo.players.network.s2c.SkinDataPacketS2C;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.WeakHashMap;

public record RequestSkinDataPacketC2S(String key) {
	public static final Identifier LOCATION = PlayersCommon.id("request_skin_data");

	private static final WeakHashMap<ServerPlayer, Long> LAST_SERVED = new WeakHashMap<>();
	private static final long MIN_GAP_MS = 100L;

	public static RequestSkinDataPacketC2S decode(FriendlyByteBuf buf) {
		return new RequestSkinDataPacketC2S(buf.readUtf());
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeUtf(key);
	}

	public static void handle(PacketContext<RequestSkinDataPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		try {
			long now = System.currentTimeMillis();
			synchronized (LAST_SERVED) {
				Long last = LAST_SERVED.get(sender);
				if (last != null && now - last < MIN_GAP_MS) return;
				LAST_SERVED.put(sender, now);
			}
			String key = ctx.message().key();
			byte[] bytes = LocalSkinStore.INSTANCE.load(key).orElse(null);
			if (bytes == null) return;
			Network.getNetworkHandler().sendToClient(new SkinDataPacketS2C(key, bytes), sender);
		} catch (Exception e) {
			Constants.LOG.error("Failed to handle skin data request", e);
		}
	}
}
