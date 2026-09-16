package dev.duzo.players.api.requests;

import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.ai.LegacyJobIds;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The identity of a request: one requester, one job, one item is one outstanding request.
 *
 * <p>Count is deliberately <b>not</b> part of the key, so asking again for a larger amount tops the
 * existing request up rather than creating a second one.
 *
 * <p>Addressed by persistent UUID because synced entity ids change on reload.
 */
public record RequestKey(UUID requester, RequesterKind kind, ResourceLocation jobId, ResourceLocation item) {

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		tag.putIntArray("Requester", UUIDUtil.uuidToIntArray(requester));
		tag.putString("Kind", kind.name());
		tag.putString("JobId", jobId.toString());
		tag.putString("Item", item.toString());
		return tag;
	}

	@Nullable
	public static RequestKey fromNbt(CompoundTag tag) {
		int[] raw = tag.getIntArray("Requester").orElse(null);
		if (raw == null || raw.length != 4) return null;
		ResourceLocation item = ResourceLocation.tryParse(tag.getStringOr("Item", ""));
		if (item == null) return null;
		ResourceLocation jobId = readJobId(tag);
		return new RequestKey(UUIDUtil.uuidFromIntArray(raw),
				RequesterKind.byName(tag.getStringOr("Kind", RequesterKind.FAKE.name()), RequesterKind.FAKE),
				jobId, item);
	}

	/**
	 * Prefers the identifier. Falls back to the legacy constant name, which is what a request key
	 * saved before the job registry holds: this key persisted {@code Job.name()}, not the ordinal
	 * {@code AIState} used, so it needs its own migration.
	 *
	 * <p>An unresolvable id is kept as written rather than collapsed to none. Count is deliberately
	 * not part of this key, so collapsing would make two unrelated fakes' requests one request.
	 */
	private static ResourceLocation readJobId(CompoundTag tag) {
		String rawId = tag.getStringOr("JobId", "");
		if (!rawId.isEmpty()) {
			ResourceLocation parsed = ResourceLocation.tryParse(rawId);
			if (parsed != null) return parsed;
		}
		ResourceLocation migrated = LegacyJobIds.byName(tag.getStringOr("Job", ""));
		return migrated == null ? FPJobs.NONE_ID : migrated;
	}
}
