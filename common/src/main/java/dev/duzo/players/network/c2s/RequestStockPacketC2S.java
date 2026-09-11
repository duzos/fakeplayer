package dev.duzo.players.network.c2s;

import commonnetwork.api.Network;
import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.api.requests.RequestStage;
import dev.duzo.players.api.requests.RequesterKind;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.Job;
import dev.duzo.players.entities.ai.requests.PoolIndex;
import dev.duzo.players.network.s2c.StockListPacketS2C;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Asks a Quartermaster what its pool currently holds, so the stock picker can be drawn. */
public record RequestStockPacketC2S(int id) {
	public static final Identifier LOCATION = PlayersCommon.id("ai_request_stock");

	public static RequestStockPacketC2S decode(FriendlyByteBuf buf) {
		return new RequestStockPacketC2S(buf.readInt());
	}

	public static void handle(PacketContext<RequestStockPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		if (!(sender.level() instanceof ServerLevel level)) return;
		if (!(level.getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		if (entity.getAIState().job() != Job.QUARTERMASTER) return;

		// The first ownership check in this codebase. Without it any client could read any player's
		// storeroom contents by entity id, and that is information the synced AIState does not
		// otherwise carry: it holds pool positions, never container contents.
		UUID owner = entity.getAIState().ownerUUID();
		if (owner == null || !owner.equals(sender.getUUID())) {
			sender.sendSystemMessage(Component.literal("That quartermaster is not yours."));
			return;
		}

		Map<Identifier, Integer> contents = PoolIndex.of(level, entity).contents();
		List<StockListPacketS2C.Entry> stock = new ArrayList<>(contents.size());
		contents.forEach((item, count) -> {
			if (count > 0) stock.add(new StockListPacketS2C.Entry(item, count));
		});
		// most-plentiful first, so the useful things are on page one
		stock.sort(Comparator.comparingInt(StockListPacketS2C.Entry::count).reversed());
		List<StockListPacketS2C.Entry> capped = stock.size() > StockListPacketS2C.MAX_ENTRIES
				? stock.subList(0, StockListPacketS2C.MAX_ENTRIES)
				: stock;
		List<StockListPacketS2C.Pending> pending = new ArrayList<>();
		for (ItemRequest request : FakePlayerRequests.outstanding(entity)) {
			if (pending.size() >= StockListPacketS2C.MAX_PENDING) break;
			pending.add(new StockListPacketS2C.Pending(
					request.key().item(),
					request.remaining(),
					// only the viewer's own requests are cancellable: a fake re-raises within a
					// second, so removing its request would look broken rather than helpful
					request.key().kind() == RequesterKind.PLAYER
							&& sender.getUUID().equals(request.key().requester()),
					request.stage() != RequestStage.DISPATCHED));
		}

		Network.getNetworkHandler().sendToClient(
				new StockListPacketS2C(ctx.message().id, List.copyOf(capped), stock.size(),
						List.copyOf(pending)), sender);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
	}
}
