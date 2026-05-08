package dev.duzo.players.entities.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

public final class AIState {
	@Nullable private UUID ownerUUID;
	private Job job = Job.NONE;
	private boolean running;
	@Nullable private BlockPos waypoint;
	@Nullable private BlockPos regionA;
	@Nullable private BlockPos regionB;
	@Nullable private BlockPos depositChest;
	private CompoundTag filter = new CompoundTag();
	private CompoundTag jobParams = new CompoundTag();
	private CompoundTag jobState = new CompoundTag();

	public AIState() {}

	@Nullable public UUID ownerUUID() { return ownerUUID; }
	public Job job() { return job; }
	public boolean running() { return running; }
	@Nullable public BlockPos waypoint() { return waypoint; }
	@Nullable public BlockPos regionA() { return regionA; }
	@Nullable public BlockPos regionB() { return regionB; }
	@Nullable public BlockPos depositChest() { return depositChest; }
	public CompoundTag filter() { return filter; }
	public CompoundTag jobParams() { return jobParams; }
	public CompoundTag jobState() { return jobState; }

	public void setOwnerUUID(@Nullable UUID uuid) { this.ownerUUID = uuid; }
	public void setJob(Job job) { this.job = job; }
	public void setRunning(boolean running) { this.running = running; }
	public void setWaypoint(@Nullable BlockPos pos) { this.waypoint = pos; }
	public void setRegionA(@Nullable BlockPos pos) { this.regionA = pos; }
	public void setRegionB(@Nullable BlockPos pos) { this.regionB = pos; }
	public void setDepositChest(@Nullable BlockPos pos) { this.depositChest = pos; }
	public void setFilter(CompoundTag filter) { this.filter = filter == null ? new CompoundTag() : filter; }
	public void setJobParams(CompoundTag params) { this.jobParams = params == null ? new CompoundTag() : params; }
	public void setJobState(CompoundTag state) { this.jobState = state == null ? new CompoundTag() : state; }

	public boolean hasOwner() { return ownerUUID != null; }

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		if (ownerUUID != null) tag.putIntArray("Owner", UUIDUtil.uuidToIntArray(ownerUUID));
		tag.putInt("Job", job.ordinal());
		tag.putBoolean("Running", running);
		if (waypoint != null) tag.putLong("Waypoint", waypoint.asLong());
		if (regionA != null) tag.putLong("RegionA", regionA.asLong());
		if (regionB != null) tag.putLong("RegionB", regionB.asLong());
		if (depositChest != null) tag.putLong("DepositChest", depositChest.asLong());
		tag.put("Filter", filter);
		tag.put("JobParams", jobParams);
		tag.put("JobState", jobState);
		return tag;
	}

	public static AIState fromNbt(CompoundTag tag) {
		AIState s = new AIState();
		if (tag == null || tag.isEmpty()) return s;
		tag.getIntArray("Owner").ifPresent(arr -> {
			if (arr.length == 4) s.ownerUUID = UUIDUtil.uuidFromIntArray(arr);
		});
		s.job = Job.byOrdinal(tag.getIntOr("Job", 0));
		s.running = tag.getBooleanOr("Running", false);
		tag.getLong("Waypoint").ifPresent(l -> s.waypoint = BlockPos.of(l));
		tag.getLong("RegionA").ifPresent(l -> s.regionA = BlockPos.of(l));
		tag.getLong("RegionB").ifPresent(l -> s.regionB = BlockPos.of(l));
		tag.getLong("DepositChest").ifPresent(l -> s.depositChest = BlockPos.of(l));
		s.filter = tag.getCompoundOrEmpty("Filter");
		s.jobParams = tag.getCompoundOrEmpty("JobParams");
		s.jobState = tag.getCompoundOrEmpty("JobState");
		return s;
	}
}
