package dev.duzo.players.entities.ai.builder;

import dev.duzo.players.Constants;
import dev.duzo.players.config.PlayersConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Name to template, server side. Names come from clients, so nothing here may leave its search roots. */
public final class SchematicStore {
	public record Result<T>(@Nullable T value, @Nullable String error) {
		public static <T> Result<T> ok(T value) {
			return new Result<>(value, null);
		}

		public static <T> Result<T> fail(String error) {
			return new Result<>(null, error);
		}

		public boolean ok() {
			return value != null;
		}
	}

	static final Path CREATE_ROOT = Paths.get("schematics");
	static final Path UPLOAD_ROOT = CREATE_ROOT.resolve("uploaded");
	static final Path CONFIG_ROOT = Paths.get("config", "players", "schematics");

	private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
	private static final long MAX_NBT_BYTES = 32L * 1024 * 1024;
	private static final int MAX_NAME = 64;

	private SchematicStore() {}

	/**
	 * Search order: the Create folder, the uploads of `uploader` (none when null or empty), the mod's own config
	 * folder, then a namespaced id through the structure manager. Other players' upload folders are never searched.
	 */
	static Result<StructureTemplate> load(ServerLevel level, String name, @Nullable String uploader) {
		if (name == null) return Result.fail("no schematic set");
		String n = name.trim();
		if (n.endsWith(".nbt")) n = n.substring(0, n.length() - 4);
		if (n.isEmpty()) return Result.fail("no schematic set");
		if (n.length() > MAX_NAME) return Result.fail("schematic name too long");
		if (n.contains("..") || n.indexOf('\\') >= 0 || n.startsWith("/") || n.startsWith(".")) {
			return Result.fail("bad schematic name");
		}
		if (n.indexOf(':') >= 0) {
			Identifier id = Identifier.tryParse(n);
			if (id == null) return Result.fail("bad schematic name");
			Optional<StructureTemplate> t = level.getStructureManager().get(id);
			return t.isPresent() ? checkSize(t.get(), name) : Result.fail("schematic not found: " + n);
		}
		if (n.indexOf('/') >= 0) return Result.fail("bad schematic name");

		List<Path> roots = new ArrayList<>();
		roots.add(CREATE_ROOT);
		if (uploader != null && isPlainName(uploader)) roots.add(UPLOAD_ROOT.resolve(uploader));
		roots.add(CONFIG_ROOT);
		for (Path root : roots) {
			Path file = resolve(root, n + ".nbt");
			if (file == null) continue;
			return read(level, file, name);
		}
		return Result.fail("schematic not found: " + n);
	}

	private static boolean isPlainName(String s) {
		return !s.isEmpty() && s.length() <= MAX_NAME && s.indexOf('/') < 0 && s.indexOf('\\') < 0
				&& s.indexOf(':') < 0 && !s.contains("..") && !s.startsWith(".");
	}

	/** The real path of `child` under `root`, or null when it is missing or resolves outside the root. */
	private static @Nullable Path resolve(Path root, String child) {
		try {
			if (!Files.isDirectory(root)) return null;
			Path realRoot = root.toRealPath();
			Path file = realRoot.resolve(child).normalize();
			if (!file.startsWith(realRoot) || !Files.isRegularFile(file)) return null;
			Path real = file.toRealPath();
			return real.startsWith(realRoot) ? real : null;
		} catch (IOException | java.nio.file.InvalidPathException e) {
			return null;
		}
	}

	private static Result<StructureTemplate> read(ServerLevel level, Path file, String name) {
		try {
			if (Files.size(file) > MAX_FILE_BYTES) return Result.fail("schematic file too large: " + name);
			CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES));
			StructureTemplate template = new StructureTemplate();
			int version = NbtUtils.getDataVersion(tag, 500);
			template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK),
					DataFixTypes.STRUCTURE.updateToCurrentVersion(level.getServer().getFixerUpper(), tag, version));
			return checkSize(template, name);
		} catch (IOException | RuntimeException e) {
			Constants.LOG.warn("could not read schematic {}", file, e);
			return Result.fail("could not read schematic: " + name);
		}
	}

	private static Result<StructureTemplate> checkSize(StructureTemplate t, String name) {
		Vec3i size = t.getSize();
		long volume = (long) size.getX() * size.getY() * size.getZ();
		int max = PlayersConfig.get().builderMaxVolume;
		if (volume <= 0) return Result.fail("schematic is empty: " + name);
		if (volume > max) return Result.fail("schematic too big: " + volume + " blocks, limit " + max);
		return Result.ok(t);
	}
}
