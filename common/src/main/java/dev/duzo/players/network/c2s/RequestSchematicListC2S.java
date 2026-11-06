package dev.duzo.players.network.c2s;

import commonnetwork.api.Network;
import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.builder.SchematicStore;
import dev.duzo.players.network.s2c.SchematicListS2C;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Asks for the schematic files the picker can offer a Builder's owner. */
public record RequestSchematicListC2S(int id) {
	public static final Identifier LOCATION = PlayersCommon.id("builder_request_schematic_list");

	public static RequestSchematicListC2S decode(FriendlyByteBuf buf) {
		return new RequestSchematicListC2S(buf.readInt());
	}

	public static void handle(PacketContext<RequestSchematicListC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		FakePlayerEntity entity = FakeLookup.resolve(ctx.sender(), ctx.message().id);
		if (entity == null) return;
		UUID owner = entity.getAIState().ownerUUID();
		if (owner == null || !owner.equals(ctx.sender().getUUID())) return;
		if (!FPJobs.is(entity.getAIState().jobId(), FPJobs.BUILDER)) return;

		if (!allow(ctx.sender().getUUID())) return;

		SchematicStore.Listing listing = SchematicStore.list(ctx.sender().getName().getString());
		Network.getNetworkHandler().sendToClient(
				new SchematicListS2C(ctx.message().id, listing.names(), listing.truncated()), ctx.sender());
	}

	private static final long MIN_GAP_MILLIS = 1000;
	private static final Map<UUID, Long> LAST = new HashMap<>();

	private static synchronized boolean allow(UUID player) {
		long now = System.currentTimeMillis();
		if (LAST.size() > 64) LAST.values().removeIf(t -> now - t > 10_000);
		Long last = LAST.get(player);
		if (last != null && now - last < MIN_GAP_MILLIS) return false;
		LAST.put(player, now);
		return true;
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
	}
}
