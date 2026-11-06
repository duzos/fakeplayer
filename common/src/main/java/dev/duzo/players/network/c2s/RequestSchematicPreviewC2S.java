package dev.duzo.players.network.c2s;

import commonnetwork.api.Network;
import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.builder.BuilderParams;
import dev.duzo.players.entities.ai.builder.SchematicPreview;
import dev.duzo.players.entities.ai.builder.SchematicStore;
import dev.duzo.players.network.s2c.SchematicPreviewS2C;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Asks for one schematic's blocks, so the picker can draw it before it is chosen. */
public record RequestSchematicPreviewC2S(int id, String name) {
	public static final Identifier LOCATION = PlayersCommon.id("builder_request_schematic_preview");

	/** Each request reads a file from disk, so a client asking faster than a person can click is ignored. */
	private static final long MIN_GAP_MILLIS = 250;
	private static final Map<UUID, Long> LAST = new HashMap<>();

	public static RequestSchematicPreviewC2S decode(FriendlyByteBuf buf) {
		return new RequestSchematicPreviewC2S(buf.readInt(), buf.readUtf(BuilderParams.MAX_NAME));
	}

	public static void handle(PacketContext<RequestSchematicPreviewC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		if (!(ctx.sender().level() instanceof ServerLevel level)) return;
		FakePlayerEntity entity = FakeLookup.resolve(ctx.sender(), ctx.message().id);
		if (entity == null) return;
		UUID owner = entity.getAIState().ownerUUID();
		if (owner == null || !owner.equals(ctx.sender().getUUID())) return;
		if (!FPJobs.is(entity.getAIState().jobId(), FPJobs.BUILDER)) return;
		if (!allow(ctx.sender().getUUID())) return;

		String name = ctx.message().name;
		SchematicStore.Result<StructureTemplate> loaded = SchematicStore.load(level, name, ctx.sender().getName().getString());
		SchematicPreviewS2C reply = loaded.ok()
				? new SchematicPreviewS2C(ctx.message().id, name, SchematicPreview.of(loaded.value()), "")
				: new SchematicPreviewS2C(ctx.message().id, name, null, loaded.error() == null ? "could not load" : loaded.error());
		Network.getNetworkHandler().sendToClient(reply, ctx.sender());
	}

	private static synchronized boolean allow(UUID player) {
		long now = System.currentTimeMillis();
		if (LAST.size() > 64) LAST.values().removeIf(t -> now - t > 10_000);
		Long last = LAST.put(player, now);
		return last == null || now - last >= MIN_GAP_MILLIS;
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(name, BuilderParams.MAX_NAME);
	}
}
