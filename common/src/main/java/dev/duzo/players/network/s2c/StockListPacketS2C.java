package dev.duzo.players.network.s2c;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * One Quartermaster's pool contents and its outstanding requests, sent in reply to a stock request.
 *
 * <p>The stock half is a snapshot on purpose: it can be stale by the time the player clicks, which
 * is fine because the request resolves server-side against the live index. Keeping it live would
 * mean the server tracking who has the screen open, for a storeroom that rarely changes.
 *
 * <p>The pending half exists because a clickable grid makes requests cheap to create, and a request
 * that shortfalls otherwise sits on the board until the player logs out. Showing them is what makes
 * cancelling them possible.
 */
public record StockListPacketS2C(int id, List<Entry> stock, int total, List<Pending> pending) implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("qm_stock_list");
	public static final CustomPacketPayload.Type<StockListPacketS2C> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, StockListPacketS2C> CODEC = CustomPacketPayload.codec(StockListPacketS2C::encode, StockListPacketS2C::decode);

	/** Cap on entries sent, so a pathological pool cannot produce an oversized packet. */
	public static final int MAX_ENTRIES = 500;
	/** Cap on outstanding rows sent. The board itself is capped at 64. */
	public static final int MAX_PENDING = 64;
	private static final int MAX_ID_LENGTH = 256;

	public record Entry(Identifier item, int count) {}

	/** An outstanding request. {@code mine} means the viewing player raised it and may cancel it. */
	public record Pending(Identifier item, int remaining, boolean mine, boolean waiting) {}

	public static StockListPacketS2C decode(FriendlyByteBuf buf) {
		int id = buf.readInt();
		int size = Math.max(0, Math.min(MAX_ENTRIES, buf.readInt()));
		List<Entry> stock = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			Identifier item = Identifier.tryParse(buf.readUtf(MAX_ID_LENGTH));
			int count = buf.readInt();
			if (item != null) stock.add(new Entry(item, count));
		}
		int total = buf.readInt();
		int pendingSize = Math.max(0, Math.min(MAX_PENDING, buf.readInt()));
		List<Pending> pending = new ArrayList<>(pendingSize);
		for (int i = 0; i < pendingSize; i++) {
			Identifier item = Identifier.tryParse(buf.readUtf(MAX_ID_LENGTH));
			int remaining = buf.readInt();
			boolean mine = buf.readBoolean();
			boolean waiting = buf.readBoolean();
			if (item != null) pending.add(new Pending(item, remaining, mine, waiting));
		}
		return new StockListPacketS2C(id, stock, total, pending);
	}

	/**
	 * What to do with a received stock list. Filled in by the client entrypoint.
	 *
	 * <p>A plain JDK interface on purpose. Naming a Screen anywhere reachable from {@link #handle}
	 * makes registering this packet verify that code, and proving a Screen subclass is assignable
	 * to Screen loads Screen, which a dedicated server refuses outright. Going through a field the
	 * server never fills keeps every client type out of the verifier's path.
	 */
	@FunctionalInterface
	public interface Opener {
		void open(int entityId, List<Entry> stock, int total, List<Pending> pending);
	}

	private static Opener opener;

	public static void setOpener(Opener value) {
		opener = value;
	}

	public static void handle(PacketContext<StockListPacketS2C> ctx) {
		if (!Side.CLIENT.equals(ctx.side())) return;
		if (opener == null) return;
		StockListPacketS2C msg = ctx.message();
		// reopened rather than mutated, so a refresh and a first open take the same path
		opener.open(msg.id(), msg.stock(), msg.total(), msg.pending());
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
		buf.writeInt(Math.min(MAX_PENDING, pending.size()));
		for (Pending row : pending) {
			buf.writeUtf(row.item().toString(), MAX_ID_LENGTH);
			buf.writeInt(row.remaining());
			buf.writeBoolean(row.mine());
			buf.writeBoolean(row.waiting());
		}
	}
}
