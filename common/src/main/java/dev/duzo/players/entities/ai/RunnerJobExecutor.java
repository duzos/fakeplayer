package dev.duzo.players.entities.ai;

import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.api.requests.RequestStage;
import dev.duzo.players.api.requests.RequesterKind;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.requests.Haul;
import dev.duzo.players.entities.ai.requests.PoolIndex;
import dev.duzo.players.entities.ai.requests.RequestDebug;
import dev.duzo.players.entities.ai.requests.RequestBoard;
import dev.duzo.players.entities.ai.requests.RequestRouting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Stateless by design. Everything this job needs is derived each tick from its Haul receipt (in its
 * own AIState) and the Quartermaster's board. There is no phase field, because collect-or-deliver
 * follows from cargo against remaining, and nothing is serialized, because the pause/resume edge
 * fires whenever the owner merely opens or closes this fake's menu.
 */
public class RunnerJobExecutor implements JobExecutor {
	private static final double SPEED = 1.0;
	private static final int PER_TICK = 1;
	private static final int MAX_PATH_FAILS = 3;
	private static final int HANDOFF_PATIENCE = 20 * 15;

	private static final double RTB_ARRIVE_SQR = 25.0;
	// JobHelpers reports UNREACHABLE on every tick of its own 40-tick retry cooldown without
	// pathing at all, and that cooldown is always set by the failed leg that just freed this
	// runner. So give up only after continuous unreachability well past that window; counting
	// reports instead of ticks tripped in three ticks and killed return-to-base every time.
	private static final int RTB_GIVE_UP_TICKS = 60;

	// all transient: nothing here is authority, so losing it on a reload costs one rescan
	@Nullable private BlockPos source;
	private int pathFails;
	private int handoffWaited;
	// where to idle between jobs. Purely cosmetic, so it is not persisted: after a reload a runner
	// simply waits where it stands until its next dispatch.
	@Nullable private UUID homeQm;
	private int rtbFails;

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		Haul haul = Haul.of(entity.getAIState());
		RequestDebug.state(entity, "haul", "{}", haul == null ? "free"
				: haul.item() + " base=" + haul.baseline() + " want=" + haul.wanted()
						+ " qm=" + RequestDebug.shortId(haul.quartermaster()));
		if (haul == null) {
			returnToBase(level, entity);
			return;
		}
		homeQm = haul.quartermaster();
		rtbFails = 0;

		if (!(level.getEntity(haul.quartermaster()) instanceof FakePlayerEntity qm)) {
			// unobservable, not gone: an unloaded chunk must not cost a dispatch. But a
			// quartermaster that really is gone would otherwise brick this runner forever, because
			// Haul presence is the busy lock and only this method can clear it.
			if (level.getGameTime() - haul.since() > RequestRouting.ORPHAN_TICKS) {
				dropCargo(level, entity, haul);
				releaseHaul(level, entity);
				RequestRouting.notifyOwner(level, entity,
						"lost contact with its quartermaster, dropping what it carried");
			}
			rest(level, entity);
			return;
		}

		if (qm.getAIState().job() != Job.QUARTERMASTER) {
			// decidable from AIState, so decide it now rather than waiting out the orphan window
			returnCargo(level, entity, qm, (ServerLevel) qm.level(), haul);
			releaseHaul(level, entity);
			rest(level, entity);
			return;
		}

		ServerLevel qmLevel = (ServerLevel) qm.level();
		RequestBoard board = RequestRouting.boardOf(qm);
		if (board == null) {
			rest(level, entity); // not ticked yet: wait, it is one tick away
			return;
		}

		ItemRequest request = board.assignedTo(entity.getUUID());
		if (request == null) {
			// cancelled, requeued, or finished by someone else: put the cargo back and go free.
			// Unconditional: the window is measured from dispatch, so a legitimately long haul
			// crosses it, and skipping the return there left the pool's goods in the runner's
			// pockets with the baseline discarded so nothing could ever account for them.
			returnCargo(level, entity, qm, qmLevel, haul);
			releaseHaul(level, entity);
			rest(level, entity);
			return;
		}

		int cargo = haul.cargo(entity);
		RequestDebug.state(entity, "task", "{} cargo={} full={}",
				RequestDebug.describe(request), cargo, JobHelpers.isFull(entity.getInventory()));
		// deliver a short load rather than hoarding it: remaining is decremented by what actually
		// arrives, so the request stays open for the rest and the requester gets what it can have
		boolean canCollectMore = !JobHelpers.isFull(entity.getInventory())
				&& nearestSource(qmLevel, entity, qm, haul) != null;
		if (cargo < request.remaining() && canCollectMore) {
			collectLeg(level, entity, qm, qmLevel, haul, request);
		} else if (cargo > 0) {
			deliverLeg(level, entity, qm, qmLevel, haul, request);
		} else {
			// carrying nothing and nothing left to collect. Says "left" because a partial delivery
			// reaches here too, and "nothing in the pool" would read as though none had arrived.
			fail(level, entity, qm, qmLevel, haul, request, "nothing left in the pool");
		}
	}

	private void collectLeg(ServerLevel level, FakePlayerEntity entity, FakePlayerEntity qm,
	                        ServerLevel qmLevel, Haul haul, ItemRequest request) {
		if (source == null) source = nearestSource(qmLevel, entity, qm, haul);
		if (source == null) return; // the tick guard re-decides next tick

		if (!JobHelpers.atTarget(entity, source)) {
			JobHelpers.WalkResult result = JobHelpers.walkTo(entity, source, SPEED);
			if (result == JobHelpers.WalkResult.UNREACHABLE && ++pathFails >= MAX_PATH_FAILS) {
				pathFails = 0;
				fail(level, entity, qm, qmLevel, haul, request, "cannot reach the storage pool");
			}
			return;
		}
		pathFails = 0;
		if (!JobHelpers.pollContainer(level, entity, source)) return;

		Container container = JobHelpers.containerAt(qmLevel, source);
		if (container == null) {
			PoolIndex.markDirty(qmLevel, qm.getUUID());
			source = null;
			return;
		}

		PoolIndex index = PoolIndex.of(qmLevel, qm);
		int want = request.remaining() - haul.cargo(entity);
		int moved = 0;
		for (int slot = 0; slot < container.getContainerSize() && moved < PER_TICK && want > 0; slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()) continue;
			if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(haul.item())) continue;
			ItemStack taken = stack.split(Math.min(want, stack.getCount()));
			// SimpleContainer.addItem copies, so taken keeps its count and the diff is the transfer
			ItemStack leftover = entity.getInventory().addItem(taken);
			int got = taken.getCount() - leftover.getCount();
			if (!leftover.isEmpty()) stack.grow(leftover.getCount());
			if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
			container.setChanged();
			if (got > 0) {
				RequestDebug.event(entity, "collect", "+{} of {} from {}", got, haul.item(), source);
				index.noteTaken(haul.item(), source, got);
				want -= got;
				moved++; // count transfers, not slots visited
			}
		}

		if (moved == 0) {
			// the index promised an item this container does not have: that is the signal to rebuild
			PoolIndex.markDirty(qmLevel, qm.getUUID());
			JobHelpers.closeContainer(level, entity);
			BlockPos spent = source;
			source = nearestSource(qmLevel, entity, qm, haul);
			if (source != null && source.equals(spent)) source = null;
		}
	}

	private void deliverLeg(ServerLevel level, FakePlayerEntity entity, FakePlayerEntity qm,
	                        ServerLevel qmLevel, Haul haul, ItemRequest request) {
		JobHelpers.closeContainer(level, entity);
		source = null;
		Entity target = requesterOf(level, request);
		if (target == null) {
			// a requester in another dimension, or briefly logged out, is unobservable rather than
			// gone: charging failures here put the request on an endless collect/fail/notify
			// treadmill. Hold the cargo and wait out the orphan window instead.
			if (level.getGameTime() - haul.since() <= RequestRouting.ORPHAN_TICKS) {
				// stop navigating, or it slides to the vanished target in a sitting pose, and reset
				// the patience counter so a returning requester gets the full window rather than
				// whatever was left when it disappeared
				entity.getNavigation().stop();
				handoffWaited = 0;
				entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING);
				return;
			}
			fail(level, entity, qm, qmLevel, haul, request, "cannot find the requester");
			return;
		}
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);

		if (!JobHelpers.atTarget(entity, target.blockPosition())) {
			entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
			handoffWaited = 0;
			JobHelpers.WalkResult result = JobHelpers.walkTo(entity, target.blockPosition(), SPEED);
			if (result == JobHelpers.WalkResult.UNREACHABLE && ++pathFails >= MAX_PATH_FAILS) {
				pathFails = 0;
				fail(level, entity, qm, qmLevel, haul, request, "cannot reach the requester");
			}
			return;
		}
		pathFails = 0;

		int delivered = handOff(entity, target, haul, request);
		RequestDebug.event(entity, "handoff", "delivered={} of {} remaining={}",
				delivered, haul.item(), request.remaining());
		if (delivered > 0) {
			handoffWaited = 0;
			entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
			RequestStage from = request.stage();
			request.setRemaining(request.remaining() - delivered);
			if (request.remaining() <= 0) {
				request.setStage(RequestStage.DELIVERED);
				FakePlayerRequests.INSTANCE.fireStageChange(qm, request, from);
				RequestBoard board = RequestRouting.boardOf(qm);
				if (board != null) board.forget(request);
				FakePlayerRequests.INSTANCE.fireRemoved(qm, request);
				returnCargo(level, entity, qm, qmLevel, haul); // any over-collected surplus
				releaseHaul(level, entity);
				rest(level, entity);
			}
			return;
		}

		// requester has no room. hold the dispatch and wait, but not forever
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING);
		if (++handoffWaited < HANDOFF_PATIENCE) return;
		handoffWaited = 0;
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
		fail(level, entity, qm, qmLevel, haul, request, "the requester has no room");
	}

	/**
	 * Moves at most what is still owed. The two branches differ deliberately:
	 * SimpleContainer.addItem copies its argument and returns the leftover, while Inventory.add
	 * mutates its argument and returns "anything moved", not "all moved". Treating them alike
	 * destroys items and over-credits the request.
	 */
	private int handOff(FakePlayerEntity entity, Entity target, Haul haul, ItemRequest request) {
		int owed = Math.min(haul.cargo(entity), request.remaining());
		int delivered = 0;
		SimpleContainer mine = entity.getInventory();
		for (int slot = 0; slot < mine.getContainerSize() && delivered < owed; slot++) {
			ItemStack stack = mine.getItem(slot);
			if (stack.isEmpty()) continue;
			if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(haul.item())) continue;

			ItemStack offer = stack.split(Math.min(owed - delivered, stack.getCount()));
			int before = offer.getCount();
			if (target instanceof ServerPlayer player) {
				player.getInventory().add(offer); // mutates offer down to the leftover
				delivered += before - offer.getCount();
				if (!offer.isEmpty()) stack.grow(offer.getCount());
			} else if (target instanceof FakePlayerEntity fake) {
				ItemStack leftover = fake.getInventory().addItem(offer); // copies, returns leftover
				delivered += before - leftover.getCount();
				if (!leftover.isEmpty()) stack.grow(leftover.getCount());
			} else {
				stack.grow(before);
			}
			if (stack.isEmpty()) mine.setItem(slot, ItemStack.EMPTY);
		}
		return delivered;
	}

	/** Give up this leg: return the cargo, free the Runner, and let the board back the request off. */
	private void fail(ServerLevel level, FakePlayerEntity entity, FakePlayerEntity qm, ServerLevel qmLevel,
	                  Haul haul, ItemRequest request, String reason) {
		RequestDebug.event(entity, "fail", "{} cargo={} reason={}",
				RequestDebug.describe(request), haul.cargo(entity), reason);
		returnCargo(level, entity, qm, qmLevel, haul);
		releaseHaul(level, entity);
		RequestStage from = request.stage();
		request.assignTo(null, level.getGameTime());
		request.setStage(RequestStage.PENDING);
		if (qm.activeJobExecutor() instanceof QuartermasterJobExecutor executor) {
			// owns the transition and fires the event, so this method must not fire a second one
			executor.noteRunnerFailure(qmLevel, qm, request, level.getGameTime(), from, reason);
		} else {
			FakePlayerRequests.INSTANCE.fireStageChange(qm, request, from);
		}
		rest(level, entity);
	}

	/**
	 * Push cargo back into the pool, dropping any remainder in world. Only cargo, never the units
	 * the Runner already held when assigned.
	 */
	private void returnCargo(ServerLevel level, FakePlayerEntity entity, FakePlayerEntity qm,
	                         ServerLevel qmLevel, Haul haul) {
		int owed = haul.cargo(entity);
		RequestDebug.event(entity, "return", "{} x{} to pool", haul.item(), owed);
		if (owed <= 0) return;
		SimpleContainer inv = entity.getInventory();

		for (BlockPos pos : FakePlayerRequests.poolOf(qm)) {
			if (owed <= 0) break;
			Container container = JobHelpers.containerAt(qmLevel, pos);
			if (container == null) continue;
			// A pooled chest may since have become a furnace, whose fuel and output slots are not
			// storage and are invisible to every read path. Insert with a real direction so the
			// sided check is honoured rather than skipping it and dropping the goods on the floor.
			Direction side = container instanceof net.minecraft.world.WorldlyContainer ? Direction.UP : null;
			for (int slot = 0; slot < inv.getContainerSize() && owed > 0; slot++) {
				ItemStack stack = inv.getItem(slot);
				if (stack.isEmpty()) continue;
				if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(haul.item())) continue;
				ItemStack give = stack.split(Math.min(owed, stack.getCount()));
				int before = give.getCount();
				// HopperBlockEntity.addItem mutates, and on the partial-merge branch returns the
				// same object, so diff against a captured count and hand it a copy
				ItemStack leftover = HopperBlockEntity.addItem(null, container, give.copy(), side);
				int moved = before - leftover.getCount();
				owed -= moved;
				if (moved < before) stack.grow(before - moved);
				if (stack.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
				container.setChanged();
			}
		}

		dropCargoRemainder(level, entity, haul, owed);
		PoolIndex.markDirty(qmLevel, qm.getUUID());
	}

	/** The pool is unknowable once the Quartermaster is gone, so the floor is the only option left. */
	private void dropCargo(ServerLevel level, FakePlayerEntity entity, Haul haul) {
		dropCargoRemainder(level, entity, haul, haul.cargo(entity));
	}

	private void dropCargoRemainder(ServerLevel level, FakePlayerEntity entity, Haul haul, int owed) {
		SimpleContainer inv = entity.getInventory();
		for (int slot = 0; slot < inv.getContainerSize() && owed > 0; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.isEmpty()) continue;
			if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(haul.item())) continue;
			ItemStack drop = stack.split(Math.min(owed, stack.getCount()));
			owed -= drop.getCount();
			entity.spawnAtLocation(level, drop);
			if (stack.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
		}
	}

	/**
	 * Drop the receipt, which is also the busy lock. A refusal would leave this Runner marked busy
	 * to every board with nothing able to clear it, so it is worth the owner's attention.
	 */
	private void releaseHaul(ServerLevel level, FakePlayerEntity entity) {
		if (Haul.clear(entity)) return;
		RequestRouting.notifyOwner(level, entity,
				"could not clear its delivery orders and will not accept more work until its state shrinks");
	}

	/** Ranked from the Runner's own position, not the Quartermaster's, which moves under follow-override. */
	@Nullable
	private BlockPos nearestSource(ServerLevel qmLevel, FakePlayerEntity entity, FakePlayerEntity qm, Haul haul) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (PoolIndex.Loc loc : PoolIndex.of(qmLevel, qm).locations(haul.item())) {
			double d = loc.pos().distSqr(entity.blockPosition());
			if (d < bestDist) {
				bestDist = d;
				best = loc.pos();
			}
		}
		return best;
	}

	/**
	 * The requester, but only while it is in this level. getPlayer(uuid) is server-wide and
	 * JobHelpers.atTarget compares coordinates without levels, so without the level check a player
	 * standing at matching coordinates in another dimension would be handed the goods.
	 */
	@Nullable
	private Entity requesterOf(ServerLevel level, ItemRequest request) {
		Entity target = request.key().kind() == RequesterKind.PLAYER
				? level.getServer().getPlayerList().getPlayer(request.key().requester())
				: level.getEntity(request.key().requester());
		return target != null && target.level() == level ? target : null;
	}

	private void rest(ServerLevel level, FakePlayerEntity entity) {
		JobHelpers.closeContainer(level, entity);
		entity.getNavigation().stop();
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
		source = null;
		handoffWaited = 0;
	}

	/**
	 * Idle back at the storeroom rather than wherever the last delivery happened to end. The
	 * Quartermaster never leaves its pool, so walking to it is walking to the storeroom, which is
	 * also where the next job will start.
	 */
	private void returnToBase(ServerLevel level, FakePlayerEntity entity) {
		JobHelpers.closeContainer(level, entity);
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
		source = null;
		handoffWaited = 0;

		if (homeQm == null || !(level.getEntity(homeQm) instanceof FakePlayerEntity qm) || !qm.isAlive()) {
			entity.getNavigation().stop();
			return;
		}
		if (entity.distanceToSqr(qm) <= RTB_ARRIVE_SQR) {
			entity.getNavigation().stop();
			homeQm = null;
			rtbFails = 0;
			return;
		}
		if (JobHelpers.walkTo(entity, qm.blockPosition(), SPEED) == JobHelpers.WalkResult.UNREACHABLE) {
			if (++rtbFails >= RTB_GIVE_UP_TICKS) {
				entity.getNavigation().stop();
				homeQm = null;
				rtbFails = 0;
			}
		} else {
			rtbFails = 0; // any progress at all resets the window
		}
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
		if (entity.level() instanceof ServerLevel level) JobHelpers.closeContainer(level, entity);
	}

	@Override
	public void onResume(FakePlayerEntity entity) {
		// nothing to restore: the Haul receipt and the board carry everything. This edge fires
		// whenever the owner opens or closes the fake's menu, which is exactly why no dispatch
		// state may live here.
		entity.getNavigation().stop();
		source = null;
	}

	@Override
	public CompoundTag serialize() {
		return new CompoundTag();
	}

	@Override
	public void deserialize(CompoundTag tag) {
		source = null;
		pathFails = 0;
		handoffWaited = 0;
	}
}
