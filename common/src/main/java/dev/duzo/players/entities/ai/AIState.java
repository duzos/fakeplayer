package dev.duzo.players.entities.ai;

import dev.duzo.players.core.FPJobs;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

public final class AIState {
	@Nullable private UUID ownerUUID;
	// the identifier is the stored truth, not the resolved JobType. A job whose mod is absent must
	// survive a read, a mutation and a save untouched, and toNbt rebuilds from scratch every time.
	private ResourceLocation jobId = FPJobs.NONE_ID;
	private boolean running;
	@Nullable private BlockPos waypoint;
	@Nullable private BlockPos regionA;
	@Nullable private BlockPos regionB;
	@Nullable private BlockPos depositChest;
	@Nullable private BlockPos sourceChest;
	private CompoundTag filter = new CompoundTag();
	private CompoundTag jobParams = new CompoundTag();
	private CompoundTag jobState = new CompoundTag();

	public AIState() {}

	@Nullable public UUID ownerUUID() { return ownerUUID; }
	public ResourceLocation jobId() { return jobId; }

	/** @return the registered job, or null when nothing is registered under {@link #jobId()}. */
	@Nullable public JobType job() { return FPJobs.get(jobId); }
	public boolean running() { return running; }
	@Nullable public BlockPos waypoint() { return waypoint; }
	@Nullable public BlockPos regionA() { return regionA; }
	@Nullable public BlockPos regionB() { return regionB; }
	@Nullable public BlockPos depositChest() { return depositChest; }
	@Nullable public BlockPos sourceChest() { return sourceChest; }
	public CompoundTag filter() { return filter; }
	public CompoundTag jobParams() { return jobParams; }
	public CompoundTag jobState() { return jobState; }

	public void setOwnerUUID(@Nullable UUID uuid) { this.ownerUUID = uuid; }
	public void setJobId(ResourceLocation id) { this.jobId = id == null ? FPJobs.NONE_ID : id; }
	public void setRunning(boolean running) { this.running = running; }
	public void setWaypoint(@Nullable BlockPos pos) { this.waypoint = pos; }
	public void setRegionA(@Nullable BlockPos pos) { this.regionA = pos; }
	public void setRegionB(@Nullable BlockPos pos) { this.regionB = pos; }
	public void setDepositChest(@Nullable BlockPos pos) { this.depositChest = pos; }
	public void setSourceChest(@Nullable BlockPos pos) { this.sourceChest = pos; }
	public void setFilter(CompoundTag filter) { this.filter = filter == null ? new CompoundTag() : filter; }
	public void setJobParams(CompoundTag params) { this.jobParams = params == null ? new CompoundTag() : params; }
	public void setJobState(CompoundTag state) { this.jobState = state == null ? new CompoundTag() : state; }

	public boolean hasOwner() { return ownerUUID != null; }

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		if (ownerUUID != null) tag.putUUID("Owner", ownerUUID);
		tag.putString("JobId", jobId.toString());
		// one release of dual-writing, so the first downgrade read of a legacy job still lands on
		// the right one. An addon job has no legacy ordinal, so it reads back as none.
		int legacy = LegacyJobIds.ordinalOf(jobId);
		tag.putInt("Job", legacy < 0 ? 0 : legacy);
		tag.putBoolean("Running", running);
		if (waypoint != null) tag.put("Waypoint", NbtUtils.writeBlockPos(waypoint));
		if (regionA != null) tag.put("RegionA", NbtUtils.writeBlockPos(regionA));
		if (regionB != null) tag.put("RegionB", NbtUtils.writeBlockPos(regionB));
		if (depositChest != null) tag.put("DepositChest", NbtUtils.writeBlockPos(depositChest));
		if (sourceChest != null) tag.put("SourceChest", NbtUtils.writeBlockPos(sourceChest));
		tag.put("Filter", filter);
		tag.put("JobParams", jobParams);
		tag.put("JobState", jobState);
		return tag;
	}

	public static AIState fromNbt(CompoundTag tag) {
		AIState s = new AIState();
		if (tag == null || tag.isEmpty()) return s;
		if (tag.hasUUID("Owner")) s.ownerUUID = tag.getUUID("Owner");
		s.jobId = readJobId(tag);
		s.running = tag.getBoolean("Running");
		NbtUtils.readBlockPos(tag, "Waypoint").ifPresent(p -> s.waypoint = p);
		NbtUtils.readBlockPos(tag, "RegionA").ifPresent(p -> s.regionA = p);
		NbtUtils.readBlockPos(tag, "RegionB").ifPresent(p -> s.regionB = p);
		NbtUtils.readBlockPos(tag, "DepositChest").ifPresent(p -> s.depositChest = p);
		NbtUtils.readBlockPos(tag, "SourceChest").ifPresent(p -> s.sourceChest = p);
		if (tag.contains("Filter")) s.filter = tag.getCompound("Filter");
		if (tag.contains("JobParams")) s.jobParams = tag.getCompound("JobParams");
		if (tag.contains("JobState")) s.jobState = tag.getCompound("JobState");
		return s;
	}

	/**
	 * Prefers the identifier. Falls back to migrating the legacy ordinal, which is what a world
	 * saved before the job registry holds. An ordinal the frozen table does not know is left as
	 * none, because there is nothing better to say about it.
	 */
	private static ResourceLocation readJobId(CompoundTag tag) {
		String raw = tag.getString("JobId");
		if (!raw.isEmpty()) {
			ResourceLocation parsed = ResourceLocation.tryParse(raw);
			if (parsed != null) return parsed;
		}
		// a tag with no Job entry at all must miss the table rather than land on none by accident,
		// which is why this reads with a -1 default instead of getInt's own default of 0.
		ResourceLocation migrated = LegacyJobIds.byOrdinal(tag.contains("Job") ? tag.getInt("Job") : -1);
		return migrated == null ? FPJobs.NONE_ID : migrated;
	}
}
