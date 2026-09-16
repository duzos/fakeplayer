package dev.duzo.players.entities.ai;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The two on-disk encodings the {@code Job} enum left behind, frozen.
 *
 * <p>{@code AIState} stored the enum's <b>ordinal</b> and {@code RequestKey} stored its
 * <b>name</b>, so a migration has to read both. Neither table may ever be edited or reordered: an
 * ordinal is a position in world save data that cannot be taken back. Appending is the only safe
 * change, and only when a constant was genuinely appended to the enum before it was deleted.
 *
 * <p>Both lookups are strict and return null for an input they do not know, so an unmapped ordinal
 * stays distinguishable from a genuine {@code players:none}. The old {@code Job.byOrdinal} could not
 * serve this purpose because it was lenient and answered {@code NONE} for anything out of range.
 *
 * <p>Identifiers are spelled out literally rather than built through {@code FPJobs}, so renaming a
 * constant there can never silently rewrite what a saved world means.
 */
public final class LegacyJobIds {
	/** Ordinal is the index. Fourteen entries, 0 to 13, in the order the enum declared them. */
	private static final ResourceLocation[] BY_ORDINAL = {
			id("none"),          // 0
			id("idle"),          // 1
			id("guard"),         // 2
			id("follow"),        // 3
			id("patrol"),        // 4
			id("deposit"),       // 5
			id("courier"),       // 6
			id("miner"),         // 7
			id("lumberjack"),    // 8
			id("fisherman"),     // 9
			id("farmer"),        // 10
			id("crafter"),       // 11
			id("quartermaster"), // 12
			id("runner"),        // 13
	};

	/** The enum constant names, as {@code RequestKey} wrote them with {@code Job.name()}. */
	private static final Map<String, ResourceLocation> BY_NAME = new HashMap<>();
	private static final Map<ResourceLocation, Integer> ORDINAL_OF = new HashMap<>();

	private static final String[] NAMES = {
			"NONE", "IDLE", "GUARD", "FOLLOW", "PATROL", "DEPOSIT", "COURIER",
			"MINER", "LUMBERJACK", "FISHERMAN", "FARMER", "CRAFTER", "QUARTERMASTER", "RUNNER",
	};

	static {
		for (int i = 0; i < BY_ORDINAL.length; i++) {
			BY_NAME.put(NAMES[i], BY_ORDINAL[i]);
			ORDINAL_OF.put(BY_ORDINAL[i], i);
		}
	}

	/**
	 * A second, independent copy of {@link #BY_ORDINAL} written out as one literal, so
	 * {@link #verify()} can catch a reorder of any of the fourteen positions, not just the two it
	 * used to spot-check. Editing the table now means editing this literal too: that friction is
	 * the point of a frozen table.
	 */
	private static final String FROZEN_ORDER =
			"players:none,players:idle,players:guard,players:follow,players:patrol,players:deposit,"
			+ "players:courier,players:miner,players:lumberjack,players:fisherman,players:farmer,"
			+ "players:crafter,players:quartermaster,players:runner";

	private LegacyJobIds() {}

	private static ResourceLocation id(String path) {
		return new ResourceLocation("players", path);
	}

	/** @return the identifier that legacy ordinal named, or null when the ordinal is unknown. */
	@Nullable
	public static ResourceLocation byOrdinal(int ordinal) {
		if (ordinal < 0 || ordinal >= BY_ORDINAL.length) return null;
		return BY_ORDINAL[ordinal];
	}

	/** @return the identifier that legacy constant name meant, or null when the name is unknown. */
	@Nullable
	public static ResourceLocation byName(@Nullable String name) {
		return name == null ? null : BY_NAME.get(name);
	}

	/**
	 * @return the legacy ordinal to dual-write for that id, or -1 when it never had one. An addon
	 *         job has no legacy ordinal, and the caller writes {@code none}'s ordinal instead.
	 */
	public static int ordinalOf(@Nullable ResourceLocation id) {
		Integer found = id == null ? null : ORDINAL_OF.get(id);
		return found == null ? -1 : found;
	}

	/**
	 * Fails mod init rather than a world load if the frozen tables have drifted. The counts are
	 * written as literals on purpose: deriving them from a length would make a dropped entry
	 * invisible, which is the exact failure that silently strands every fake holding that ordinal.
	 */
	public static void verify() {
		if (BY_ORDINAL.length != 14) {
			throw new IllegalStateException("the frozen job ordinal table must have 14 entries, found "
					+ BY_ORDINAL.length);
		}
		if (NAMES.length != 14) {
			throw new IllegalStateException("the frozen job name table must have 14 entries, found "
					+ NAMES.length);
		}
		if (BY_NAME.size() != 14 || ORDINAL_OF.size() != 14) {
			throw new IllegalStateException("the frozen job tables contain duplicates: "
					+ BY_NAME.size() + " names, " + ORDINAL_OF.size() + " ids");
		}
		String actualOrder = Arrays.stream(BY_ORDINAL)
				.map(ResourceLocation::toString)
				.collect(Collectors.joining(","));
		if (!actualOrder.equals(FROZEN_ORDER)) {
			throw new IllegalStateException("the frozen job ordinal table has been reordered, "
					+ "expected [" + FROZEN_ORDER + "] but found [" + actualOrder + "]");
		}
	}
}
