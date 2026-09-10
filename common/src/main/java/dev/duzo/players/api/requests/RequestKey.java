package dev.duzo.players.api.requests;

import dev.duzo.players.entities.ai.Job;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

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
public record RequestKey(UUID requester, RequesterKind kind, Job job, Identifier item) {

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		tag.putIntArray("Requester", UUIDUtil.uuidToIntArray(requester));
		tag.putString("Kind", kind.name());
		tag.putString("Job", job.name());
		tag.putString("Item", item.toString());
		return tag;
	}

	@Nullable
	public static RequestKey fromNbt(CompoundTag tag) {
		int[] raw = tag.getIntArray("Requester").orElse(null);
		if (raw == null || raw.length != 4) return null;
		Identifier item = Identifier.tryParse(tag.getStringOr("Item", ""));
		if (item == null) return null;
		Job job;
		try {
			job = Job.valueOf(tag.getStringOr("Job", Job.NONE.name()));
		} catch (IllegalArgumentException e) {
			job = Job.NONE;
		}
		return new RequestKey(UUIDUtil.uuidFromIntArray(raw),
				RequesterKind.byName(tag.getStringOr("Kind", RequesterKind.FAKE.name()), RequesterKind.FAKE),
				job, item);
	}
}
