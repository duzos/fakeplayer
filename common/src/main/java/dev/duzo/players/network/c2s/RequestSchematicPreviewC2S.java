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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.Util;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Asks for one schematic's blocks, so the picker can draw it before it is chosen. */
public record RequestSchematicPreviewC2S(int id, String name) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("builder_request_schematic_preview");

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
			reply(ctx.sender(), error(id, name, "no preview for structure ids"));
			return;
		}
		if (!allow(ctx.sender().getUUID())) {
			reply(ctx.sender(), SchematicPreviewS2C.busy(id, name));
			return;
		}

		ServerPlayer sender = ctx.sender();
		String uploader = sender.getName().getString();
		Path file = SchematicStore.locate(name, uploader);
		Key key = file == null ? null : keyOf(file);
		SchematicPreview.Data hit = key == null ? null : cached(key);
		if (hit != null) {
			reply(sender, forData(id, name, hit));
			return;
		}
		UUID player = sender.getUUID();
		if (!begin(player)) {
			reply(sender, SchematicPreviewS2C.busy(id, name));
			return;
		}
		MinecraftServer server = level.getServer();
		// Only the file read, the NBT parse and the flattening run here. readStructure(CompoundTag) builds
		// a fresh template from the data fixer and the frozen block registry, and nothing else of the
		// level is touched, so none of it needs the server thread.
		Util.ioPool().execute(() -> {
			try {
				SchematicPreview.Data loadedData = null;
				String failure = null;
				try {
					SchematicStore.Result<StructureTemplate> loaded = SchematicStore.load(level, name, uploader);
					if (loaded.ok()) loadedData = SchematicPreview.of(loaded.value());
					else failure = loaded.error() == null ? "could not load" : loaded.error();
				} catch (Throwable t) {
					failure = "could not load";
				}
				SchematicPreview.Data done = loadedData;
				String error = failure;
				server.execute(() -> {
					if (done != null && key != null) remember(key, done);
					ServerPlayer online = server.getPlayerList().getPlayer(player);
					if (online == null) return;
					reply(online, done != null ? forData(id, name, done) : error(id, name, error));
				});
			} finally {
				end(player);
			}
		});
	}

	/** Called when a server stops, so a load that never finished cannot keep a slot for the next world. */
	public static synchronized void reset() {
		IN_FLIGHT.clear();
		LAST.clear();
	}

	private static SchematicPreviewS2C forData(int id, String name, SchematicPreview.Data data) {
		return data.withinCaps() ? new SchematicPreviewS2C(id, name, data, "", false) : error(id, name, "too large to preview");
	}

	private static final int MAX_IN_FLIGHT = 2;
	private static final Set<UUID> IN_FLIGHT = new HashSet<>();

	/** One load per player and two overall; anything past that is told to come back. */
	private static synchronized boolean begin(UUID player) {
		if (IN_FLIGHT.size() >= MAX_IN_FLIGHT || IN_FLIGHT.contains(player)) return false;
		IN_FLIGHT.add(player);
		return true;
	}

	private static synchronized void end(UUID player) {
		IN_FLIGHT.remove(player);
	}

	private static SchematicPreviewS2C error(int id, String name, String message) {
		return new SchematicPreviewS2C(id, name, null, message, false);
	}

	private static void reply(ServerPlayer to, SchematicPreviewS2C message) {
		Network.getNetworkHandler().sendToClient(message, to);
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

	private static Key keyOf(Path file) {
		try {
			return new Key(file, Files.getLastModifiedTime(file).toMillis(), Files.size(file), PlayersConfig.get().builderMaxVolume);
		} catch (IOException e) {
			return null;
		}
	}

	private static synchronized SchematicPreview.Data cached(Key key) {
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
