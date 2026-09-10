package dev.duzo.players.entities.ai;

import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.api.requests.RequestStage;
import dev.duzo.players.api.requests.RequesterKind;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.requests.Haul;
import dev.duzo.players.entities.ai.requests.PoolIndex;
import dev.duzo.players.entities.ai.requests.RequestBoard;
import dev.duzo.players.entities.ai.requests.RequestRouting;
import net.minecraft.core.BlockPos;
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

	// all transient: nothing here is authority, so losing it on a reload costs one rescan
	@Nullable private BlockPos source;
	private int pathFails;
	private int handoffWaited;

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		Haul haul = Haul.of(entity.getAIState());
		if (haul == null) {
			rest(level, entity);
			return;
		}

		if (!(level.getEntity(haul.quartermaster()) instanceof FakePlayerEntity qm)) {
			// unobservable, not gone: an unloaded chunk must not cost a dispatch. But a
			// quartermaster that really is gone would otherwise brick this runner forever, because
			// Haul presence is the busy lock and only this method can clear it.
			if (level.getGameTime() - haul.since() > RequestRouting.ORPHAN_TICKS) {
				dropCargo(level, entity, haul);
				Haul.clear(entity);
				RequestRouting.notifyOwner(level, entity,
						"lost contact with its quartermaster, dropping what it carried");
			}
			rest(level, entity);
			return;
		}

		if (qm.getAIState().job() != Job.QUARTERMASTER) {
			// decidable from AIState, so decide it now rather than waiting out the orphan window
			returnCargo(level, entity, qm, (ServerLevel) qm.level(), haul);
			Haul.clear(entity);
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
			// A receipt older than the orphan window is not evidence of anything any more (the fake
			// may have been re-jobbed and picked the item up since), so drop it without returning.
			if (level.getGameTime() - haul.since() <= RequestRouting.ORPHAN_TICKS) {
				returnCargo(level, entity, qm, qmLevel, haul);
			}
			Haul.clear(entity);
			rest(level, entity);
			return;
		}

		int cargo = haul.cargo(entity);
		// deliver a short load rather than hoarding it: remaining is decremented by what actually
		// arrives, so the request stays open for the rest and the requester gets what it can have
		boolean canCollectMore = !JobHelpers.isFull(entity.getInventory())
				&& nearestSource(qmLevel, entity, qm, haul) != null;
		if (cargo < request.remaining() && canCollectMore) {
			collectLeg(level, entity, qm, qmLevel, haul, request);
		} else if (cargo > 0) {
			deliverLeg(level, entity, qm, qmLevel, haul, request);
		} else {
			fail(level, entity, qm, qmLevel, haul, request, "nothing in the pool");
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
			fail(level, entity, qm, qmLevel, haul, request, "cannot find the requester");
			return;
		}

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
				Haul.clear(entity);
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
		returnCargo(level, entity, qm, qmLevel, haul);
		Haul.clear(entity);
		RequestStage from = request.stage();
		request.assignTo(null, level.getGameTime());
		request.setStage(RequestStage.PENDING);
		if (qm.activeJobExecutor() instanceof QuartermasterJobExecutor executor) {
			// owns the transition and fires the event, so this method must not fire a second one
			executor.noteRunnerFailure(qmLevel, qm, request, level.getGameTime(), reason);
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
		if (owed <= 0) return;
		SimpleContainer inv = entity.getInventory();

		for (BlockPos pos : FakePlayerRequests.poolOf(qm)) {
			if (owed <= 0) break;
			Container container = JobHelpers.containerAt(qmLevel, pos);
			if (container == null) continue;
			for (int slot = 0; slot < inv.getContainerSize() && owed > 0; slot++) {
				ItemStack stack = inv.getItem(slot);
				if (stack.isEmpty()) continue;
				if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(haul.item())) continue;
				ItemStack give = stack.split(Math.min(owed, stack.getCount()));
				int before = give.getCount();
				// HopperBlockEntity.addItem mutates, and on the partial-merge branch returns the
				// same object, so diff against a captured count and hand it a copy
				ItemStack leftover = HopperBlockEntity.addItem(null, container, give.copy(), null);
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
