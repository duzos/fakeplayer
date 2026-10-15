package dev.duzo.players.entities.possession;

import dev.duzo.players.Constants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One entry per fake that has ever been possessed: its epoch, whether a possession is live, and after a release the state
 * handed back, until the fake that received it has been saved. Chunks and the player file save on their own schedules, so
 * this is what tells a stale copy of a fake from the current one. It has its own file, written synchronously, so a
 * transition can be made durable without flushing the rest of the world.
 */
public final class PossessionLedger {
	private static final String FILE = "players_possession.dat";

	private static PossessionLedger current;
	private static MinecraftServer currentServer;

	public static final class Entry {
		public final long epoch;
		public final UUID possessor;
		public final boolean active;
		/** The state handed back to the fake on release, serialised. Null once a saved copy of the fake holds it. */
		@Nullable
		public final CompoundTag pending;
		/** Not persisted: the dimension in which the current-epoch copy of the fake was serialised, so that dimension's next save may drop {@link #pending}. */
		@Nullable
		String pendingSeenIn;
		/** Not persisted: a stale copy already took {@link #pending} this session, so any other stale copy is a duplicate. */
		boolean pendingAdopted;

		public Entry(long epoch, UUID possessor, boolean active, @Nullable CompoundTag pending) {
			this.epoch = epoch;
			this.possessor = possessor;
			this.active = active;
			this.pending = pending;
		}

		public boolean pendingAdopted() {
			return pendingAdopted;
		}

		public void markPendingAdopted() {
			pendingAdopted = true;
		}
	}

	private final Path path;
	private final Map<UUID, Entry> entries = new HashMap<>();

	private PossessionLedger(Path path) {
		this.path = path;
	}

	static PossessionLedger at(Path path) {
		PossessionLedger ledger = new PossessionLedger(path);
		ledger.load();
		return ledger;
	}

	/** The ledger of this server's world, read on first use. */
	public static PossessionLedger of(MinecraftServer server) {
		if (current == null || currentServer != server) {
			PossessionLedger ledger = new PossessionLedger(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE));
			ledger.load();
			current = ledger;
			currentServer = server;
		}
		return current;
	}

	public static void forget() {
		current = null;
		currentServer = null;
	}

	@Nullable
	public Entry get(UUID fake) {
		return entries.get(fake);
	}

	/** Sets the fake's entry and writes the file. On failure the previous entry is put back and false is returned. */
	public boolean put(UUID fake, Entry entry) {
		Entry previous = entries.put(fake, entry);
		if (write()) {
			Constants.traceEvent("ledger write fake={} epoch={} active={} possessor={} pending={}", fake, entry.epoch, entry.active,
					entry.possessor, entry.pending != null);
			return true;
		}
		if (previous == null) entries.remove(fake);
		else entries.put(fake, previous);
		Constants.traceEvent("ledger write FAILED fake={} epoch={}", fake, entry.epoch);
		return false;
	}

	/** The current-epoch copy of a fake was serialised, so a pending state it holds is now in its chunk's save. */
	public void markSeenSaved(UUID fake, long epoch, String dimension) {
		Entry entry = entries.get(fake);
		if (entry != null && entry.pending != null && !entry.active && entry.epoch == epoch) entry.pendingSeenIn = dimension;
	}

	/** After one dimension saved: forgets the pending state of every fake serialised in it, and rewrites the file if any went. */
	public void onLevelSaved(String dimension) {
		dropSeen(dimension);
	}

	/** After every dimension saved. */
	public void onAllSaved() {
		dropSeen(null);
	}

	private void dropSeen(@Nullable String dimension) {
		boolean changed = false;
		for (Map.Entry<UUID, Entry> e : new HashMap<>(entries).entrySet()) {
			Entry old = e.getValue();
			if (old.pendingSeenIn == null || (dimension != null && !dimension.equals(old.pendingSeenIn))) continue;
			entries.put(e.getKey(), new Entry(old.epoch, old.possessor, old.active, null));
			Constants.traceEvent("ledger pending dropped fake={} epoch={}", e.getKey(), old.epoch);
			changed = true;
		}
		if (changed) write();
	}

	private void load() {
		if (!Files.exists(path)) return;
		try {
			CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
			ListTag list = root.getListOrEmpty("entries");
			for (int i = 0; i < list.size(); i++) {
				CompoundTag tag = list.getCompoundOrEmpty(i);
				UUID fake = uuid(tag, "fake");
				UUID possessor = uuid(tag, "possessor");
				if (fake == null || possessor == null) continue;
				CompoundTag pending = tag.getCompound("pending").orElse(null);
				entries.put(fake, new Entry(tag.getLongOr("epoch", 0L), possessor, tag.getBooleanOr("active", false), pending));
			}
			Constants.traceEvent("ledger loaded {} entries", entries.size());
		} catch (IOException | RuntimeException e) {
			Constants.LOG.error("players_possession.dat is unreadable, moving it aside and starting empty", e);
			entries.clear();
			try {
				Files.move(path, path.resolveSibling(FILE + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException moveFailed) {
				Constants.LOG.error("could not move the unreadable ledger aside", moveFailed);
			}
		}
	}

	private boolean write() {
		CompoundTag root = new CompoundTag();
		ListTag list = new ListTag();
		for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
			Entry entry = e.getValue();
			CompoundTag tag = new CompoundTag();
			tag.putIntArray("fake", UUIDUtil.uuidToIntArray(e.getKey()));
			tag.putIntArray("possessor", UUIDUtil.uuidToIntArray(entry.possessor));
			tag.putLong("epoch", entry.epoch);
			tag.putBoolean("active", entry.active);
			if (entry.pending != null) tag.put("pending", entry.pending.copy());
			list.add(tag);
		}
		root.put("entries", list);
		Path temp = path.resolveSibling(FILE + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			NbtIo.writeCompressed(root, temp);
			Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException | RuntimeException e) {
			Constants.LOG.error("could not write the possession ledger", e);
			return false;
		}
	}

	@Nullable
	private static UUID uuid(CompoundTag tag, String key) {
		return tag.getIntArray(key).filter(a -> a.length == 4).map(UUIDUtil::uuidFromIntArray).orElse(null);
	}
}
