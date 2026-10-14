package dev.duzo.players.entities.goal;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/**
 * An idle fake claims the nearest free bed when it is dark, walks to it and sleeps until it is light, it is hurt, the
 * bed goes, or it has something to do. Never runs for a fake that has work to do, and never takes a bed an online
 * player respawns at.
 */
public class SleepInBedGoal extends Goal {
	private static final int SEARCH_RANGE = 24;
	private static final int CHECK_TICKS = 40;
	private static final int FAIL_COOLDOWN = 400;
	private static final int WAKE_COOLDOWN = 200;
	private static final int HURT_COOLDOWN = 600;
	private static final int WALK_TIMEOUT = 20 * 30;

	private final FakePlayerEntity fake;
	private BlockPos bed;
	private boolean sleeping;
	private int walkTicks;
	private long cooldownUntil;

	public SleepInBedGoal(FakePlayerEntity fake) {
		this.fake = fake;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
	}

	@Override
	public boolean canUse() {
		if (!(this.fake.level() instanceof ServerLevel level)) return false;
		BlockPos claim = this.fake.bedClaim();
		// a reload while asleep: the claim and the sleeping position both persisted, so carry on
		if (claim != null && this.fake.isBedSleeping() && this.fake.getSleepingPos().filter(claim::equals).isPresent()) {
			this.bed = claim;
			this.sleeping = true;
			return true;
		}
		if (!this.fake.isIdleNow() || !this.fake.isStanding() || this.fake.isBedSleeping()) return false;
		long now = level.getGameTime();
		if (now < this.cooldownUntil) return false;
		if ((now + this.fake.getId()) % CHECK_TICKS != 0) return false;
		if (!dark(level, this.fake) || this.fake.isInWater() || !this.fake.onGround()) return false;

		BlockPos found = findBed(level);
		if (found == null) {
			this.cooldownUntil = now + FAIL_COOLDOWN;
			return false;
		}
		Path path = this.fake.getNavigation().createPath(found, 0);
		if (path == null || !path.canReach()) {
			this.cooldownUntil = now + FAIL_COOLDOWN;
			return false;
		}
		if (level.getPoiManager().take(h -> h.is(PoiTypes.HOME), (h, p) -> p.equals(found), found, 1).isEmpty()) {
			return false;
		}
		this.bed = found;
		this.sleeping = false;
		this.fake.setBedClaim(found);
		Constants.traceEvent("bed claim {} at {}", this.fake.getUUID(), found);
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		if (!(this.fake.level() instanceof ServerLevel level)) return false;
		if (this.bed == null || !this.bed.equals(this.fake.bedClaim())) return false;
		if (!this.fake.isIdleNow() || !this.fake.isStanding() || !dark(level, this.fake)) return false;
		if (this.sleeping) return this.fake.isBedSleeping();
		return this.walkTicks < WALK_TIMEOUT && bedUsable(level, this.bed);
	}

	@Override
	public void start() {
		this.walkTicks = 0;
		if (!this.sleeping) this.moveToBed();
	}

	@Override
	public void tick() {
		if (this.sleeping) return;
		this.walkTicks++;
		if (this.bed.closerToCenterThan(this.fake.position(), 1.14D) && Math.abs(this.fake.getY() - this.bed.getY()) < 1.5D) {
			this.fake.getNavigation().stop();
			this.fake.startSleeping(this.bed);
			this.sleeping = true;
			Constants.traceEvent("bed sleep {} at {}", this.fake.getUUID(), this.bed);
		} else if (this.fake.getNavigation().isDone()) {
			this.moveToBed();
		}
	}

	@Override
	public void stop() {
		boolean wasSleeping = this.sleeping;
		boolean night = this.fake.level() instanceof ServerLevel level && dark(level, this.fake);
		boolean hurt = this.fake.consumeWokenByDamage();
		if (this.fake.isBedSleeping()) this.fake.stopSleeping();
		else this.fake.getNavigation().stop();
		this.fake.releaseBedClaim();
		if (wasSleeping) Constants.traceEvent("bed wake {} night={}", this.fake.getUUID(), night);
		this.sleeping = false;
		this.bed = null;
		long now = this.fake.level().getGameTime();
		// a hurt fake gets longer for the threat to pass
		this.cooldownUntil = now + (wasSleeping && hurt ? HURT_COOLDOWN : WAKE_COOLDOWN);
	}

	@Override
	public boolean isInterruptable() {
		return !this.sleeping;
	}

	private void moveToBed() {
		this.fake.getNavigation().moveTo(this.bed.getX() + 0.5D, this.bed.getY(), this.bed.getZ() + 0.5D, 1.0D);
	}

	public static boolean dark(ServerLevel level, FakePlayerEntity fake) {
		return level.dimensionType().bedWorks() && !level.isDay();
	}

	private BlockPos findBed(ServerLevel level) {
		PoiManager poi = level.getPoiManager();
		return poi.findClosest(h -> h.is(PoiTypes.HOME), p -> bedUsable(level, p) && !isRespawnPoint(level, p),
				this.fake.blockPosition(), SEARCH_RANGE, PoiManager.Occupancy.HAS_SPACE).orElse(null);
	}

	private boolean bedUsable(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return false;
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof BedBlock)) return false;
		if (state.hasProperty(BedBlock.PART) && state.getValue(BedBlock.PART) != BedPart.HEAD) return false;
		return !(state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED));
	}

	private static boolean isRespawnPoint(ServerLevel level, BlockPos head) {
		BlockState state = level.getBlockState(head);
		BlockPos foot = state.hasProperty(BedBlock.FACING) ? head.relative(state.getValue(BedBlock.FACING).getOpposite()) : head;
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			BlockPos spawn = player.getRespawnPosition();
			if (spawn == null || player.getRespawnDimension() != level.dimension()) continue;
			if (spawn.equals(head) || spawn.equals(foot)) return true;
		}
		return false;
	}
}
