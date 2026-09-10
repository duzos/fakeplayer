package dev.duzo.players.entities.ai;

import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.api.requests.RequestKey;
import dev.duzo.players.api.requests.RequestStage;
import dev.duzo.players.api.requests.RequesterKind;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.requests.Haul;
import dev.duzo.players.entities.ai.requests.PoolIndex;
import dev.duzo.players.entities.ai.requests.RequestBoard;
import dev.duzo.players.entities.ai.requests.RequestRouting;
import dev.duzo.players.entities.ai.requests.StoragePool;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

public class QuartermasterJobExecutor implements JobExecutor {
	private static final int RESOLVE_EVERY = 10;
	private static final int MAX_FAILURES = 3;
	private static final int BACKOFF_TICKS = 20 * 15;
	private static final int PRUNE_EVERY = 20 * 60;

	private RequestBoard board = new RequestBoard();
	private int cooldown;
	private long nextShortfallRetry;
	private long nextPrune;
	private boolean scheduled;

	public RequestBoard board() {
		return this.board;
	}

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		// the quartermaster stays put: it must keep resolving while deliveries are in flight
		entity.getNavigation().stop();

		long now = level.getGameTime();
		if (!scheduled) {
			// seed both timers relative to load, or the first tick after every restart prunes every
			// persisted request whose requester has not logged in or loaded yet
			scheduled = true;
			nextPrune = now + PRUNE_EVERY;
			nextShortfallRetry = now + retryTicks();
		}

		if (cooldown-- > 0) return;
		cooldown = RESOLVE_EVERY;

		auditAssignments(level, entity, now);

		if (now >= nextPrune) {
			nextPrune = now + PRUNE_EVERY;
			// the age floor keeps "unloaded chunk" from reading as "requester gone", the same
			// distinction assignmentFault makes
			board.prune(key -> requesterGone(level, key), MAX_FAILURES,
					now - RequestRouting.ORPHAN_TICKS,
					dropped -> FakePlayerRequests.INSTANCE.fireRemoved(entity, dropped));
		}

		if (now >= nextShortfallRetry) {
			nextShortfallRetry = now + retryTicks();
			retryShortfalls(entity);
		}

		if (StoragePool.read(entity.getAIState()).isEmpty()) return;

		ItemRequest request = board.nextPending(now);
		if (request != null) resolve(level, entity, request, now);
	}

	private static long retryTicks() {
		return 20L * Math.max(1, PlayersConfig.get().requestShortfallRetrySeconds);
	}

	private void resolve(ServerLevel level, FakePlayerEntity entity, ItemRequest request, long now) {
		int have = PoolIndex.of(level, entity).count(request.key().item());

		String liar = null;
		if (have < request.remaining()) {
			// one forced rebuild up front, so the first resolver's base is fresh; after that each
			// post-call reading is itself freshly rebuilt and serves as the next resolver's base.
			// Rebuilding on both sides of every call cost 2N full storeroom rescans per pass.
			PoolIndex.markDirty(level, entity.getUUID());
			have = PoolIndex.of(level, entity).count(request.key().item());
			for (FakePlayerRequests.Resolver resolver : FakePlayerRequests.INSTANCE.resolverChain()) {
				int before = have;
				int claimed = Math.max(0, resolver.deposit(entity, request));
				// unconditionally, because a resolver that deposits correctly but returns 0 would
				// otherwise stay invisible until the next interval rebuild
				PoolIndex.markDirty(level, entity.getUUID());
				have = PoolIndex.of(level, entity).count(request.key().item());
				if (claimed > 0 && claimed > have - before) liar = resolver.name();
				if (have >= request.remaining()) break;
			}
		}

		// trust the index, never a resolver's word
		if (have <= 0) {
			shortfall(level, entity, request, liar);
			return;
		}

		FakePlayerEntity runner = RequestRouting.nearestFreeRunner(level, entity);
		if (runner == null) {
			// the most common first-run misconfiguration, and silent in every earlier revision
			announce(level, entity, request, "norunner", "no free runner for " + request.key().item());
			return;
		}
		// every reason that can precede a successful dispatch, or a later genuine one is swallowed
		board.clearLatch(request.key(), "norunner");
		board.clearLatch(request.key(), "haulfailed");
		board.clearLatch(request.key(), "orphaned");

		// a refused receipt leaves the runner unmarked while the request says DISPATCHED, which is
		// exactly the double-assignment window Haul exists to close
		if (!Haul.write(runner, entity.getUUID(), request.key().item(), now)) {
			request.noteFailure();
			request.setRetryAfter(now + BACKOFF_TICKS);
			announce(level, entity, request, "haulfailed", "could not hand a runner its delivery orders");
			// refusal is a persistent property of that runner's oversized state, so escalate
			// rather than looping on it forever with nothing more said
			if (request.failures() >= MAX_FAILURES) {
				RequestStage stalled = request.stage();
				request.setStage(RequestStage.SHORTFALL);
				request.setShortfallReason("could not hand a runner its delivery orders");
				FakePlayerRequests.INSTANCE.fireStageChange(entity, request, stalled);
			}
			return;
		}

		RequestStage from = request.stage();
		request.assignTo(runner.getUUID(), now);
		request.setStage(RequestStage.DISPATCHED);
		request.setShortfallReason(null);
		// deliberately NOT resetFailures(): dispatch happens before the runner can fail, so
		// clearing here means the counter never reaches MAX_FAILURES and escalation is dead code

		// only a dispatch that can satisfy the whole ask clears the shortfall latch, or a
		// trickle-fed pool re-notifies the owner once per item that arrives
		if (have >= request.remaining()) board.clearLatch(request.key(), "shortfall");
		FakePlayerRequests.INSTANCE.fireStageChange(entity, request, from);
	}

	/**
	 * Requeue assignments whose Runner was re-jobbed, or which have pointed at an unresolvable
	 * entity for ORPHAN_TICKS. Reads AIState only, so it cannot mistake a Runner that has simply
	 * not ticked yet for a dead one.
	 */
	private void auditAssignments(ServerLevel level, FakePlayerEntity entity, long now) {
		for (ItemRequest request : board.dispatched()) {
			RequestRouting.AssignmentFault fault =
					RequestRouting.assignmentFault(level, entity.getUUID(), request, now);
			if (fault == null) continue;
			RequestStage from = request.stage();
			request.assignTo(null, now);
			request.setStage(RequestStage.PENDING);
			request.noteFailure();
			request.setRetryAfter(now + BACKOFF_TICKS);
			if (fault == RequestRouting.AssignmentFault.ORPHANED) {
				announce(level, entity, request, "orphaned",
						"a runner carrying " + request.key().item() + " never came back, asking again");
			}
			FakePlayerRequests.INSTANCE.fireStageChange(entity, request, from);
		}
	}

	/**
	 * Called by a Runner that gave up a leg, so the request backs off instead of re-dispatching at
	 * once. {@code from} is passed in because the caller has already moved the stage off DISPATCHED,
	 * and re-reading it here would make the listener event a no-op.
	 */
	public void noteRunnerFailure(ServerLevel level, FakePlayerEntity entity, ItemRequest request,
	                              long now, RequestStage from, String reason) {
		request.noteFailure();
		request.setRetryAfter(now + BACKOFF_TICKS);
		if (request.failures() >= MAX_FAILURES) {
			request.setStage(RequestStage.SHORTFALL);
			request.setShortfallReason(reason);
			announce(level, entity, request, "shortfall", reason + " for " + request.key().item());
		}
		FakePlayerRequests.INSTANCE.fireStageChange(entity, request, from);
	}

	private void retryShortfalls(FakePlayerEntity entity) {
		int room = PlayersConfig.get().requestMaxPerQuartermaster - board.openCount();
		for (ItemRequest request : board.all()) {
			if (room <= 0) break;
			if (request.stage() != RequestStage.SHORTFALL) continue;
			RequestStage from = request.stage();
			request.setStage(RequestStage.PENDING);
			request.resetFailures();
			request.setRetryAfter(0L);
			room--;
			FakePlayerRequests.INSTANCE.fireStageChange(entity, request, from);
		}
	}

	private boolean requesterGone(ServerLevel level, RequestKey key) {
		if (key.kind() == RequesterKind.PLAYER) {
			return level.getServer().getPlayerList().getPlayer(key.requester()) == null;
		}
		return level.getEntity(key.requester()) == null;
	}

	private void shortfall(ServerLevel level, FakePlayerEntity entity, ItemRequest request, @Nullable String liar) {
		RequestStage from = request.stage();
		request.setStage(RequestStage.SHORTFALL);
		// counted, or prune can never drop it and a request from a dead requester re-dispatches forever
		request.noteFailure();
		String reason = liar == null
				? "cannot fill a request for " + request.key().item() + ", waiting for stock"
				: "resolver " + liar + " reported stock it did not deposit";
		request.setShortfallReason(reason);
		announce(level, entity, request, "shortfall", reason);
		FakePlayerRequests.INSTANCE.fireStageChange(entity, request, from);
	}

	/**
	 * Tell the owner once per reason. The latch is only set when the message was actually delivered,
	 * so a shortfall that happens while the owner is offline is still reported when they return.
	 */
	private void announce(ServerLevel level, FakePlayerEntity entity, ItemRequest request, String reason, String message) {
		if (board.isLatched(request.key(), reason)) return;
		if (RequestRouting.notifyOwner(level, entity, message)) board.latch(request.key(), reason);
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
	}

	@Override
	public void onResume(FakePlayerEntity entity) {
		if (entity.level() instanceof ServerLevel level) PoolIndex.markDirty(level, entity.getUUID());
	}

	@Override
	public CompoundTag serialize() {
		return board.toNbt();
	}

	@Override
	public void deserialize(CompoundTag tag) {
		this.board = RequestBoard.fromNbt(tag);
		this.scheduled = false;
	}
}
