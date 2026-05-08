package dev.duzo.players.entities.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

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
		if (ownerUUID != null) tag.putUUID("Owner", ownerUUID);
		tag.putInt("Job", job.ordinal());
		tag.putBoolean("Running", running);
		if (waypoint != null) tag.put("Waypoint", NbtUtils.writeBlockPos(waypoint));
		if (regionA != null) tag.put("RegionA", NbtUtils.writeBlockPos(regionA));
		if (regionB != null) tag.put("RegionB", NbtUtils.writeBlockPos(regionB));
		if (depositChest != null) tag.put("DepositChest", NbtUtils.writeBlockPos(depositChest));
		tag.put("Filter", filter);
		tag.put("JobParams", jobParams);
		tag.put("JobState", jobState);
		return tag;
	}

	public static AIState fromNbt(CompoundTag tag) {
		AIState s = new AIState();
		if (tag == null || tag.isEmpty()) return s;
		if (tag.hasUUID("Owner")) s.ownerUUID = tag.getUUID("Owner");
		s.job = Job.byOrdinal(tag.getInt("Job"));
		s.running = tag.getBoolean("Running");
		if (tag.contains("Waypoint")) s.waypoint = NbtUtils.readBlockPos(tag.getCompound("Waypoint"));
		if (tag.contains("RegionA")) s.regionA = NbtUtils.readBlockPos(tag.getCompound("RegionA"));
		if (tag.contains("RegionB")) s.regionB = NbtUtils.readBlockPos(tag.getCompound("RegionB"));
		if (tag.contains("DepositChest")) s.depositChest = NbtUtils.readBlockPos(tag.getCompound("DepositChest"));
		if (tag.contains("Filter")) s.filter = tag.getCompound("Filter");
		if (tag.contains("JobParams")) s.jobParams = tag.getCompound("JobParams");
		if (tag.contains("JobState")) s.jobState = tag.getCompound("JobState");
		return s;
	}
}
