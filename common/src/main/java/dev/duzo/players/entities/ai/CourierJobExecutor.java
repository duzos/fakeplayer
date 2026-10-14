package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.FakeProblem;
import dev.duzo.players.entities.ai.requests.StockReserve;
import dev.duzo.players.entities.speech.FakeSpeech;
import dev.duzo.players.entities.speech.Verbosity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

public class CourierJobExecutor implements JobExecutor {
	private static final double ARRIVAL_DIST_SQR = 6.0;
	private static final double SPEED = 1.0;
	private static final int TRANSFER_PER_TICK = 1;
	private static final int IDLE_REPATH_COOLDOWN = 40;

	private static final int RETRY_WAIT_TICKS = 20 * 15;

	private enum Phase { TO_SOURCE, PULL, TO_DEPOSIT, DUMP, WAITING_AT_DEPOSIT }

	private Phase phase = Phase.TO_SOURCE;
	private int repathCooldown;
	private long waitUntilTick = 0L;
	private String lastBlocker = "";

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		AIState state = entity.getAIState();
		BlockPos source = state.sourceChest();
		BlockPos deposit = state.depositChest();
		if (source == null || deposit == null) return;

		if (phase != Phase.PULL && phase != Phase.DUMP && phase != Phase.WAITING_AT_DEPOSIT) JobHelpers.closeContainer(level, entity);

		switch (phase) {
			case TO_SOURCE -> walkTo(entity, source, Phase.PULL);
			case PULL -> {
				Container src = JobHelpers.containerAt(level, source);
				if (src == null) {
					JobHelpers.closeContainer(level, entity);
					phase = Phase.TO_DEPOSIT;
					return;
				}
				if (entity.blockPosition().distSqr(source) > ARRIVAL_DIST_SQR) {
					JobHelpers.closeContainer(level, entity);
					phase = Phase.TO_SOURCE;
					return;
				}
				if (!JobHelpers.pollContainer(level, entity, source)) return; // open + pause ~1s before pulling
				SimpleContainer dest = entity.getInventory();
				if (JobHelpers.isFull(dest)) {
					phase = Phase.TO_DEPOSIT;
					return;
				}
				int moved = pullMatching(src, dest, state.filter(), TRANSFER_PER_TICK);
				if (moved == 0) phase = Phase.TO_DEPOSIT;
				else resumed(entity); // making progress again - let the next problem re-announce
			}
			case TO_DEPOSIT -> walkTo(entity, deposit, Phase.DUMP);
			case DUMP -> {
				Container dst = JobHelpers.containerAt(level, deposit);
				if (dst == null) {
					JobHelpers.closeContainer(level, entity);
					phase = Phase.TO_SOURCE;
					return;
				}
				if (entity.blockPosition().distSqr(deposit) > ARRIVAL_DIST_SQR) {
					JobHelpers.closeContainer(level, entity);
					phase = Phase.TO_DEPOSIT;
					return;
				}
				if (!JobHelpers.pollContainer(level, entity, deposit)) return; // open + pause ~1s before depositing
				SimpleContainer src = entity.getInventory();
				int moved = dumpAll(src, dst, TRANSFER_PER_TICK, StockReserve.of(entity));
				if (moved == 0) {
					// nothing left our inventory and it's still full: the deposit chest has no room, not just empty
					// pockets - cycling straight back to PULL would only bounce between the two chests forever
					if (JobHelpers.isFull(src)) { waitForBlocker(level, entity, "courier: deposit chest full"); return; }
					phase = Phase.TO_SOURCE;
				} else {
					resumed(entity);
				}
			}
			case WAITING_AT_DEPOSIT -> tickWaitingAtDeposit(level, entity, deposit);
		}
	}

	private void tickWaitingAtDeposit(ServerLevel level, FakePlayerEntity entity, BlockPos deposit) {
		Container dst = JobHelpers.containerAt(level, deposit);
		if (dst != null && entity.blockPosition().distSqr(deposit) <= ARRIVAL_DIST_SQR && JobHelpers.pollContainer(level, entity, deposit)) {
			int moved = dumpAll(entity.getInventory(), dst, TRANSFER_PER_TICK, StockReserve.of(entity));
			if (moved > 0) { // owner made room - resume the normal drain
				entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
				resumed(entity);
				phase = Phase.DUMP;
				return;
			}
		}
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING);
		if (level.getGameTime() < waitUntilTick) return;
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
		phase = Phase.DUMP; // re-check; waitForBlocker fires again if still full
	}

	private void waitForBlocker(ServerLevel level, FakePlayerEntity entity, String message) {
		entity.reportProblem(FakeProblem.fromBlocker(message));
		waitUntilTick = level.getGameTime() + RETRY_WAIT_TICKS;
		entity.getNavigation().stop();
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING);
		phase = Phase.WAITING_AT_DEPOSIT;
		if (!message.equals(lastBlocker)) { // tell the owner once per distinct problem, not every retry
			if (FakeSpeech.settle(entity, Verbosity.NORMAL, "blocked:" + message, message + " - waiting 15s before retry")) lastBlocker = message;
		}
	}

	private void walkTo(FakePlayerEntity entity, BlockPos target, Phase onArrive) {
		if (entity.blockPosition().distSqr(target) <= ARRIVAL_DIST_SQR) {
			entity.getNavigation().stop();
			phase = onArrive;
			repathCooldown = 0;
			return;
		}
		if (--repathCooldown > 0) return;
		if (entity.getNavigation().isDone()) {
			entity.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, SPEED);
			repathCooldown = IDLE_REPATH_COOLDOWN;
		}
	}

	private int pullMatching(Container src, SimpleContainer dst, CompoundTag filter, int budget) {
		int moved = 0;
		for (int i = 0; i < src.getContainerSize() && moved < budget; i++) {
			ItemStack stack = src.getItem(i);
			if (stack.isEmpty()) continue;
			if (!matchesFilter(stack, filter)) continue;
			if (JobHelpers.withdrawInto(src, i, stack.getCount(), dst) > 0) {
				dst.setChanged();
				moved++;
			}
		}
		return moved;
	}

	private int dumpAll(SimpleContainer src, Container dst, int budget, StockReserve reserve) {
		int moved = 0;
		for (int i = 0; i < src.getContainerSize() && moved < budget; i++) {
			ItemStack stack = src.getItem(i);
			if (stack.isEmpty()) continue;
			if (JobHelpers.depositSurplus(src, i, dst, reserve)) continue;
			if (JobHelpers.moveSlot(src, i, dst) > 0) {
				src.setChanged();
				moved++;
			}
		}
		return moved;
	}

	/** Same grammar the miner uses: {@code namespace:id}, {@code #namespace:tag}, {@code *}, comma-separated. */
	private boolean matchesFilter(ItemStack stack, CompoundTag filter) {
		String raw = filter == null ? "" : filter.getStringOr("Tag", "");
		// absent or empty means no filter has ever been set for this fake - match everything, not the miner's
		// "c:ores" default, since every deployed courier has a blank filter today
		if (raw == null || raw.isBlank() || raw.equals("*")) return true;
		for (String part : raw.split(",")) {
			String token = part.trim();
			if (token.isEmpty()) continue;
			if (JobHelpers.matchesFilterToken(stack, token)) return true;
		}
		return false;
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
		if (entity.level() instanceof ServerLevel sl) JobHelpers.closeContainer(sl, entity);
	}

	@Override public void onResume(FakePlayerEntity entity) {}

	@Override
	public CompoundTag serialize() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Phase", phase.name());
		tag.putLong("WaitUntil", waitUntilTick);
		return tag;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) return;
		String name = tag.getStringOr("Phase", "");
		if (!name.isEmpty()) {
			try { phase = Phase.valueOf(name); } catch (IllegalArgumentException ignored) {}
		}
		waitUntilTick = tag.getLongOr("WaitUntil", 0L);
	}

	/** Work is moving again: forget the blocker, and its speech latch so a recurrence is announced. */
	private void resumed(FakePlayerEntity entity) {
		FakeSpeech.clearBlocked(entity, lastBlocker);
		lastBlocker = "";
		entity.clearProblem();
	}
}
