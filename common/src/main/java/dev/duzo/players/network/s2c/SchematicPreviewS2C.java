package dev.duzo.players.network.s2c;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.ai.builder.BuilderParams;
import dev.duzo.players.entities.ai.builder.SchematicPreview;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** One schematic's blocks for the picker's preview, or the reason it could not be loaded. */
public record SchematicPreviewS2C(int id, String name, SchematicPreview.@Nullable Data data, String error,
                                boolean busy) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("builder_schematic_preview");

	private static final int MAX_ERROR = 256;

	public static SchematicPreviewS2C decode(FriendlyByteBuf buf) {
		int id = buf.readInt();
		String name = buf.readUtf(BuilderParams.MAX_NAME);
		String error = buf.readUtf(MAX_ERROR);
		boolean busy = buf.readBoolean();
		if (!buf.readBoolean()) return new SchematicPreviewS2C(id, name, null, error, busy);
		int sx = Math.max(0, Math.min(SchematicPreview.MAX_SIDE, buf.readVarInt()));
		int sy = Math.max(0, Math.min(SchematicPreview.MAX_SIDE, buf.readVarInt()));
		int sz = Math.max(0, Math.min(SchematicPreview.MAX_SIDE, buf.readVarInt()));
		int total = buf.readVarInt();
		int[] palette = new int[Math.max(0, Math.min(SchematicPreview.MAX_PALETTE, buf.readVarInt()))];
		for (int i = 0; i < palette.length; i++) palette[i] = buf.readVarInt();
		int cells = Math.max(0, Math.min(SchematicPreview.MAX_BLOCKS, buf.readVarInt()));
		int[] cellState = new int[cells];
		int[] cellPos = new int[cells];
		for (int i = 0; i < cells; i++) {
			cellState[i] = buf.readVarInt();
			cellPos[i] = buf.readVarInt();
		}
		return new SchematicPreviewS2C(id, name, new SchematicPreview.Data(sx, sy, sz, palette, cellState, cellPos, total), "", false);
	}

	/** Filled in by the client entrypoint, for the same reason as {@link StockListPacketS2C.Opener}. */
	@FunctionalInterface
	public interface Receiver {
		void receive(int entityId, String name, SchematicPreview.@Nullable Data data, String error, boolean busy);
	}

	private static Receiver receiver;

	public static void setReceiver(Receiver value) {
		receiver = value;
	}

	public static void handle(PacketContext<SchematicPreviewS2C> ctx) {
		if (!Side.CLIENT.equals(ctx.side())) return;
		if (receiver == null) return;
		SchematicPreviewS2C msg = ctx.message();
		receiver.receive(msg.id(), msg.name(), msg.data(), msg.error(), msg.busy());
	}

	/** The server was asked too soon after the last request and did nothing; ask again shortly. */
	public static SchematicPreviewS2C busy(int id, String name) {
		return new SchematicPreviewS2C(id, name, null, "", true);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(name, BuilderParams.MAX_NAME);
		buf.writeUtf(error, MAX_ERROR);
		buf.writeBoolean(busy);
		buf.writeBoolean(data != null);
		if (data == null) return;
		buf.writeVarInt(data.sizeX());
		buf.writeVarInt(data.sizeY());
		buf.writeVarInt(data.sizeZ());
		buf.writeVarInt(data.total());
		buf.writeVarInt(data.palette().length);
		for (int state : data.palette()) buf.writeVarInt(state);
		buf.writeVarInt(data.cells());
		for (int i = 0; i < data.cells(); i++) {
			buf.writeVarInt(data.cellState()[i]);
			buf.writeVarInt(data.cellPos()[i]);
		}
	}
}
