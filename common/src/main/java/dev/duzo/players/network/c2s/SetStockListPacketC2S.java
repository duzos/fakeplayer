package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.requests.StockList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Sets a fake's keep-stocked list from the AI menu's Stock box. */
public record SetStockListPacketC2S(int id, String list) {
	public static final Identifier LOCATION = PlayersCommon.id("ai_set_stock");
	private static final int MAX_LENGTH = 512;

	public static SetStockListPacketC2S decode(FriendlyByteBuf buf) {
		return new SetStockListPacketC2S(buf.readInt(), buf.readUtf(MAX_LENGTH));
	}

	public static void handle(PacketContext<SetStockListPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		// a stock list is standing server-side work: repeated quartermaster scans, runner
		// dispatches and draw on the owner's pool. Unlike the one-shot marker packets it is worth
		// an ownership check, so it cannot be installed on someone else's fake.
		UUID owner = entity.getAIState().ownerUUID();
		if (owner == null || !owner.equals(ctx.sender().getUUID())) return;
		// normalized server side, so a hand-built packet cannot store an unbounded or unparseable
		// list that every later read has to defend against
		if (!entity.mutateAIState(s -> StockList.write(s, ctx.message().list()))) {
			Constants.LOG.warn("Could not store a stock list for {}: its AIState is too large to sync",
					entity.getUUID());
			ctx.sender().sendSystemMessage(net.minecraft.network.chat.Component.literal(
					entity.getName().getString() + ": could not save that stock list, its state is too large"));
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(list, MAX_LENGTH);
	}
}
