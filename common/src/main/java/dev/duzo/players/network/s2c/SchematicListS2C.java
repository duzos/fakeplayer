package dev.duzo.players.network.s2c;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.ai.builder.BuilderParams;
import dev.duzo.players.entities.ai.builder.SchematicStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** The schematic files a Builder's owner can pick from, sent in reply to a list request. */
public record SchematicListS2C(int id, List<String> names, boolean truncated) implements CustomPacketPayload {
	public static final Identifier LOCATION = PlayersCommon.id("builder_schematic_list");
	public static final CustomPacketPayload.Type<SchematicListS2C> TYPE = new CustomPacketPayload.Type<>(LOCATION);
	public static final StreamCodec<FriendlyByteBuf, SchematicListS2C> CODEC = CustomPacketPayload.codec(SchematicListS2C::encode, SchematicListS2C::decode);

	public static SchematicListS2C decode(FriendlyByteBuf buf) {
		int id = buf.readInt();
		int size = Math.max(0, Math.min(SchematicStore.MAX_LIST, buf.readVarInt()));
		List<String> names = new ArrayList<>(size);
		for (int i = 0; i < size; i++) names.add(buf.readUtf(BuilderParams.MAX_NAME));
		return new SchematicListS2C(id, names, buf.readBoolean());
	}

	/** Filled in by the client entrypoint, for the same reason as {@link StockListPacketS2C.Opener}. */
	@FunctionalInterface
	public interface Receiver {
		void receive(int entityId, List<String> names, boolean truncated);
	}

	private static Receiver receiver;

	public static void setReceiver(Receiver value) {
		receiver = value;
	}

	public static void handle(PacketContext<SchematicListS2C> ctx) {
		if (!Side.CLIENT.equals(ctx.side())) return;
		if (receiver == null) return;
		SchematicListS2C msg = ctx.message();
		receiver.receive(msg.id(), msg.names(), msg.truncated());
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		int size = Math.min(SchematicStore.MAX_LIST, names.size());
		buf.writeVarInt(size);
		for (int i = 0; i < size; i++) buf.writeUtf(names.get(i), BuilderParams.MAX_NAME);
		buf.writeBoolean(truncated);
	}
}
