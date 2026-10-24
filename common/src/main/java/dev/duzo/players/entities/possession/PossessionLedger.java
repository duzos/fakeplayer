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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PossessionLedger {
	private static final String FILE = "players_possession.dat";
	private static PossessionLedger current;
	private static MinecraftServer currentServer;

	public static final class Entry {
		public final long epoch;
		public final UUID possessor;
		public final boolean active;
		@Nullable public final CompoundTag pending;
		@Nullable public final CompoundTag stash;
		@Nullable public final CompoundTag shell;
		public final String dimension;
		public final boolean death;
		public final long sequence;
		public final String playerToken;
		private boolean pendingAdopted;

		public Entry(long epoch, UUID possessor, boolean active, @Nullable CompoundTag pending) {
			this(epoch, possessor, active, pending, null, null, "", false, 0, "");
		}

		public Entry(long epoch, UUID possessor, boolean active, @Nullable CompoundTag pending,
					 @Nullable CompoundTag stash, @Nullable CompoundTag shell, String dimension,
					 boolean death, long sequence, String playerToken) {
			this.epoch = epoch;
			this.possessor = possessor;
			this.active = active;
			this.pending = pending;
			this.stash = stash;
			this.shell = shell;
			this.dimension = dimension;
			this.death = death;
			this.sequence = sequence;
			this.playerToken = playerToken;
		}

		public boolean pendingAdopted() { return pendingAdopted; }
		public void markPendingAdopted() { pendingAdopted = true; }
		public boolean hasOutput() { return !active && pending != null; }

		public Entry checkpointed() {
			return new Entry(epoch, possessor, false, null, null, null, dimension, death,
					sequence, playerToken);
		}
	}

	private final Path path;
	private final Map<UUID, Entry> entries = new HashMap<>();
	private boolean available = true;
	private long sequence;

	private PossessionLedger(Path path) { this.path = path; }

	static PossessionLedger at(Path path) {
		PossessionLedger ledger = new PossessionLedger(path);
		ledger.load();
		return ledger;
	}

	public static PossessionLedger of(MinecraftServer server) {
		if (current == null || currentServer != server) {
			current = at(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE));
			currentServer = server;
		}
		return current;
	}

	public static void forget() { current = null; currentServer = null; }
	public boolean available() { return available; }
	public long nextSequence() { return ++sequence; }
	@Nullable public Entry get(UUID fake) { return entries.get(fake); }
	public Map<UUID, Entry> entries() { return Map.copyOf(entries); }

	public Map.@Nullable Entry<UUID, Entry> latestFor(UUID player) {
		return entries.entrySet().stream().filter(e -> e.getValue().possessor.equals(player))
				.max(java.util.Comparator.comparingLong(e -> e.getValue().sequence)).orElse(null);
	}

	public boolean put(UUID fake, Entry entry) {
		if (!available) return false;
		Entry previous = entries.put(fake, entry);
		if (write()) {
			Constants.traceEvent("ledger write fake={} epoch={} active={} possessor={} pending={}",
					fake, entry.epoch, entry.active, entry.possessor, entry.pending != null);
			return true;
		}
		if (previous == null) entries.remove(fake); else entries.put(fake, previous);
		Constants.traceEvent("ledger write FAILED fake={} epoch={}", fake, entry.epoch);
		return false;
	}

	private void load() {
		if (Files.notExists(path)) return;
		try {
			CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
			ListTag list = root.getList("entries").orElseThrow(() -> new IOException("missing entries"));
			for (int i = 0; i < list.size(); i++) {
				CompoundTag tag = list.getCompoundOrEmpty(i);
				UUID fake = uuid(tag, "fake"), possessor = uuid(tag, "possessor");
				if (fake == null || possessor == null) throw new IOException("invalid ownership entry");
				Entry entry = new Entry(tag.getLongOr("epoch", 0L), possessor, tag.getBooleanOr("active", false),
						tag.getCompound("pending").orElse(null), tag.getCompound("stash").orElse(null),
						tag.getCompound("shell").orElse(null), tag.getStringOr("dimension", ""),
						tag.getBooleanOr("death", false),
						tag.getLongOr("sequence", 0L), tag.getStringOr("player_token", ""));
				if (entry.sequence == 0 && (entry.active || entry.pending != null)) throw new IOException("incomplete experimental possession record has no recovery journal");
				entries.put(fake, entry);
				sequence = Math.max(sequence, entry.sequence);
			}
		} catch (IOException | RuntimeException e) {
			available = false;
			entries.clear();
			Constants.LOG.error("The possession ledger is unreadable; possession and fake reconciliation are quarantined. Repair the file and restart.", e);
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
			if (entry.stash != null) tag.put("stash", entry.stash.copy());
			if (entry.shell != null) tag.put("shell", entry.shell.copy());
			tag.putString("dimension", entry.dimension);
			tag.putBoolean("death", entry.death);
			tag.putLong("sequence", entry.sequence);
			tag.putString("player_token", entry.playerToken);
			list.add(tag);
		}
		root.put("entries", list);
		Path temp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			if (PossessionFaults.failWrite()) throw new IOException("injected ledger write failure");
			Files.createDirectories(path.getParent());
			NbtIo.writeCompressed(root, temp);
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
			Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException | RuntimeException e) {
			Constants.LOG.error("could not write the possession ledger", e);
			return false;
		}
	}

	@Nullable
	static UUID uuid(CompoundTag tag, String key) {
		return tag.getIntArray(key).filter(a -> a.length == 4).map(UUIDUtil::uuidFromIntArray).orElse(null);
	}
}
