package dev.duzo.players.network.s2c;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.client.screen.QuartermasterStockScreen;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * One Quartermaster's pool contents, sent in reply to a stock request, and the screen that shows
 * them.
 *
 * <p>A snapshot, deliberately: it can be stale by the time the player clicks, and that is fine
 * because the request is resolved server-side against the live index. Keeping it live would mean
 * the server tracking who has the screen open, for a storeroom that rarely changes.
 */
public record StockListPacketS2C(int id, List<Entry> stock, int total) implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("qm_stock_list");
	public static final CustomPacketPayload.Type<StockListPacketS2C> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, StockListPacketS2C> CODEC = CustomPacketPayload.codec(StockListPacketS2C::encode, StockListPacketS2C::decode);

	/** Cap on entries sent, so a pathological pool cannot produce an oversized packet. */
	public static final int MAX_ENTRIES = 500;
	private static final int MAX_ID_LENGTH = 256;

	public record Entry(Identifier item, int count) {}

	public static StockListPacketS2C decode(FriendlyByteBuf buf) {
		int id = buf.readInt();
		int size = Math.max(0, Math.min(MAX_ENTRIES, buf.readInt()));
		List<Entry> stock = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			Identifier item = Identifier.tryParse(buf.readUtf(MAX_ID_LENGTH));
			int count = buf.readInt();
			if (item != null) stock.add(new Entry(item, count));
		}
		return new StockListPacketS2C(id, stock, buf.readInt());
	}

	public static void handle(PacketContext<StockListPacketS2C> ctx) {
		if (!Side.CLIENT.equals(ctx.side())) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) return;
		if (!(minecraft.level.getEntity(ctx.message().id()) instanceof FakePlayerEntity entity)) return;
		minecraft.setScreen(new QuartermasterStockScreen(entity, ctx.message().stock(), ctx.message().total()));
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeInt(Math.min(MAX_ENTRIES, stock.size()));
		for (Entry entry : stock) {
			buf.writeUtf(entry.item().toString(), MAX_ID_LENGTH);
			buf.writeInt(entry.count());
		}
		buf.writeInt(total);
	}
}
