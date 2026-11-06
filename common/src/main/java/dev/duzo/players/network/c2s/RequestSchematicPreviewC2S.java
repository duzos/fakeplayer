package dev.duzo.players.network.c2s;

import commonnetwork.api.Network;
import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.config.PlayersConfig;
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
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
		String name = ctx.message().name;
		int id = ctx.message().id;
		// a structure id would make the structure manager remember the miss for good
		if (name.indexOf(':') >= 0) {
			reply(ctx, error(id, name, "no preview for structure ids"));
			return;
		}
		if (!allow(ctx.sender().getUUID())) {
			reply(ctx, SchematicPreviewS2C.busy(id, name));
			return;
		}

		String uploader = ctx.sender().getName().getString();
		Path file = SchematicStore.locate(name, uploader);
		Key key = file == null ? null : keyOf(file);
		SchematicPreview.Data data = key == null ? null : cached(key);
		if (data == null) {
			SchematicStore.Result<StructureTemplate> loaded = SchematicStore.load(level, name, uploader);
			if (!loaded.ok()) {
				reply(ctx, error(id, name, loaded.error() == null ? "could not load" : loaded.error()));
				return;
			}
			data = SchematicPreview.of(loaded.value());
			if (key != null) remember(key, data);
		}
		if (!data.withinCaps()) {
			reply(ctx, error(id, name, "too large to preview"));
			return;
		}
		reply(ctx, new SchematicPreviewS2C(id, name, data, "", false));
	}

	private static SchematicPreviewS2C error(int id, String name, String message) {
		return new SchematicPreviewS2C(id, name, null, message, false);
	}

	private static void reply(PacketContext<RequestSchematicPreviewC2S> ctx, SchematicPreviewS2C message) {
		Network.getNetworkHandler().sendToClient(message, ctx.sender());
	}

	/** A file schematic is the same flattened result until the file or the volume limit changes. */
	private record Key(Path file, long modified, long size, int maxVolume) {}

	private static final int CACHE_SIZE = 16;
	private static final Map<Key, SchematicPreview.Data> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, SchematicPreview.Data> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	private static @Nullable Key keyOf(Path file) {
		try {
			return new Key(file, Files.getLastModifiedTime(file).toMillis(), Files.size(file), PlayersConfig.get().builderMaxVolume);
		} catch (IOException e) {
			return null;
		}
	}

	private static synchronized SchematicPreview.@Nullable Data cached(Key key) {
		return CACHE.get(key);
	}

	private static synchronized void remember(Key key, SchematicPreview.Data data) {
		CACHE.put(key, data);
	}

	/** Only a request that is served counts, so a dropped one does not push the next one further out. */
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
		buf.writeUtf(name, BuilderParams.MAX_NAME);
	}
}
