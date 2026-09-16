package dev.duzo.players.entities.ai;

import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.api.requests.RequestKey;
import dev.duzo.players.api.requests.RequestStage;
import dev.duzo.players.api.requests.RequesterKind;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.requests.CraftPlan;
import dev.duzo.players.entities.ai.requests.Commission;
import dev.duzo.players.entities.ai.requests.Haul;
import dev.duzo.players.entities.ai.requests.PoolIndex;
import dev.duzo.players.entities.ai.requests.RequestBoard;
import dev.duzo.players.entities.ai.requests.RequestRouting;
import dev.duzo.players.entities.ai.requests.StoragePool;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

public class QuartermasterJobExecutor implements JobExecutor {
	private static final int RESOLVE_EVERY = 10;
	private static final int MAX_FAILURES = 3;
	private static final int BACKOFF_TICKS = 20 * 15;
	private static final int PRUNE_EVERY = 20 * 60;
	private static final String HAUL_REFUSED = "could not hand a runner its delivery orders";

	/** Outcome of asking a Crafter to make the gap. */
	private enum Escalation {
		/** A commission is in hand. The request waits rather than reporting anything. */
		CRAFTING,
		/** The chain was planned and came up short. The missing list is the shortfall reason. */
		MISSING,
		/** Nothing to escalate to: no recipe, or no Crafter able to take it. */
		NONE
	}

	/** An escalation outcome, with the reason to report when there is one worth reporting. */
	private record Escalated(Escalation state, @Nullable String reason) {
		static final Escalated CRAFTING = new Escalated(Escalation.CRAFTING, null);
		static final Escalated NONE = new Escalated(Escalation.NONE, null);
	}

	/** Which Crafter is making a request's gap, and when to stop waiting on it. */
	private record Craft(UUID crafter, long deadline) {}

	// transient, like every other scheduling detail here. After a reload a CRAFTING request finds
	// no entry, goes back to PENDING and is resolved against the pool, which is where a commission
	// that did finish will have put its goods anyway.
	private final Map<RequestKey, Craft> crafts = new HashMap<>();

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
		auditCrafts(level, entity, now);

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
			List<FakePlayerRequests.Resolver> chain = FakePlayerRequests.INSTANCE.resolverChain();
			if (!chain.isEmpty()) {
				PoolIndex.markDirty(level, entity.getUUID());
				have = PoolIndex.of(level, entity).count(request.key().item());
			}
			for (FakePlayerRequests.Resolver resolver : chain) {
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

		// stock, then craft, then report. Escalating on a short pool rather than only on an empty
		// one is what "make the gap" means: a request for 64 planks against a pool holding 1 is a
		// gap of 63, and waiting for the pool to reach zero first would never close it.
		Escalated escalated = have < request.remaining()
				? escalate(level, entity, request, have, now)
				: Escalated.NONE;

		// trust the index, never a resolver's word
		if (have <= 0) {
			if (escalated.state() == Escalation.CRAFTING) return;
			shortfall(level, entity, request, liar, escalated.reason(), now);
			return;
		}

		FakePlayerEntity runner = RequestRouting.nearestFreeRunner(level, entity);
		if (runner == null) {
			// the most common first-run misconfiguration, and silent in every earlier revision
			announce(level, entity, request, "norunner", "no free runner for " + request.key().item());
			return;
		}
		board.clearLatch(request.key(), "norunner");

		// a refused receipt leaves the runner unmarked while the request says DISPATCHED, which is
		// exactly the double-assignment window Haul exists to close
		if (!Haul.write(runner, entity.getUUID(), request.key().item(), request.remaining(), now)) {
			request.noteFailure();
			request.setRetryAfter(now + BACKOFF_TICKS);
			announce(level, entity, request, "haulfailed", HAUL_REFUSED);
			// refusal is a persistent property of that runner's oversized state, so escalate
			// rather than looping on it forever with nothing more said
			if (request.failures() >= MAX_FAILURES) {
				RequestStage stalled = request.stage();
				request.setStage(RequestStage.SHORTFALL);
				request.setShortfallReason(HAUL_REFUSED);
				FakePlayerRequests.INSTANCE.fireStageChange(entity, request, stalled);
			}
			return;
		}

		// cleared only now the dispatch has actually succeeded: clearing before the attempt meant
		// the latch never suppressed anything and every retry re-announced
		board.clearLatch(request.key(), "haulfailed");
		board.clearLatch(request.key(), "orphaned");

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
	 * Ask a bonded Crafter to make what the pool is short of.
	 *
	 * <p>Plans against a copy of the pool, so a plan that comes up short has spent nothing and can
	 * be reported whole. A plan that only misses the requested item itself is not a craft problem
	 * at all, it is an empty storeroom, and is left to the ordinary shortfall message.
	 */
	private Escalated escalate(ServerLevel level, FakePlayerEntity entity, ItemRequest request,
	                           int have, long now) {
		if (crafts.containsKey(request.key())) return Escalated.CRAFTING;

		Identifier item = request.key().item();
		int gap = request.remaining() - Math.max(0, have);
		if (gap <= 0) return Escalated.NONE;
		CraftPlan plan = CraftPlan.of(level.getServer(), PoolIndex.of(level, entity).contents(), item, gap);
		if (!plan.complete()) {
			// nothing could be planned at all, so there is no missing list worth reading: saying
			// "short of 64 oak planks" when oak planks are what was asked for tells nobody anything
			if (plan.unplannable()) return Escalated.NONE;
			Map<Identifier, Integer> missing = plan.missing();
			if (missing.size() == 1 && missing.containsKey(item)) return Escalated.NONE;
			return new Escalated(Escalation.MISSING,
					"cannot craft " + gap + " " + item + ": short of " + plan.describeMissing());
		}

		FakePlayerEntity crafter = RequestRouting.nearestFreeCrafter(level, entity);
		if (crafter == null) {
			announce(level, entity, request, "nocrafter",
					"needs " + gap + " " + item + " crafting and has no free crafter with a table");
			return Escalated.NONE;
		}

		List<Commission.Entry> steps = new ArrayList<>(plan.steps().size());
		for (CraftPlan.Step step : plan.steps()) {
			steps.add(new Commission.Entry(step.recipe(), step.times(), step.perRun()));
		}
		Commission commission = new Commission(entity.getUUID(), steps, 0, item, gap, now);
		if (!Commission.write(crafter, commission)) {
			announce(level, entity, request, "commissionfailed",
					"could not hand a crafter its orders for " + item);
			return Escalated.NONE;
		}

		crafts.put(request.key(), new Craft(crafter.getUUID(),
				now + 20L * Math.max(5, PlayersConfig.get().requestCraftTimeoutSeconds)));
		board.clearLatch(request.key(), "nocrafter");
		board.clearLatch(request.key(), "commissionfailed");
		// a request that still has stock to dispatch keeps its own stage: it is about to go out
		// with a runner, and the craft is topping the pool up behind it
		if (have <= 0) {
			RequestStage from = request.stage();
			request.setStage(RequestStage.CRAFTING);
			request.setShortfallReason(null);
			FakePlayerRequests.INSTANCE.fireStageChange(entity, request, from);
		}
		return Escalated.CRAFTING;
	}

	/**
	 * Stop waiting on a craft once it is done, gone or overdue. A finished commission is not a
	 * failure: the goods are in the pool and the next resolve pass will find them, so only a
	 * timeout charges the request.
	 */
	private void auditCrafts(ServerLevel level, FakePlayerEntity entity, long now) {
		// deliberately not skipped when the map is empty: a CRAFTING request restored from a save
		// has no entry here at all, and would otherwise sit in a stage nothing ever resolves
		// a request that left the board takes its craft entry with it, or the map grows forever
		crafts.keySet().removeIf(key -> board.find(key) == null);

		for (ItemRequest request : board.all()) {
			Craft craft = crafts.get(request.key());
			if (craft == null) {
				// restored from a save, where the map is empty by design. Nothing is watching it,
				// so put it back in play and let the next pass find whatever the craft delivered.
				if (request.stage() == RequestStage.CRAFTING) requeue(entity, request, now, false);
				continue;
			}

			boolean overdue = now >= craft.deadline();
			if (!overdue && stillCrafting(level, entity, craft)) continue;
			crafts.remove(request.key());

			if (overdue) {
				// cancel the orders as well as the wait. Leaving them set keeps that crafter busy
				// against every future escalation while nothing is watching what it produces.
				cancelCommission(level, entity, craft);
				announce(level, entity, request, "craftslow",
						"gave up waiting for " + request.key().item() + " to be crafted");
			}
			// a request that has since been dispatched keeps its stage: the craft was topping the
			// pool up behind a runner that is already on its way
			if (request.stage() != RequestStage.CRAFTING) continue;
			// charged only when the craft delivered nothing, or an escalation that fails the same
			// way every time never reaches the shortfall the owner needs to see
			boolean delivered = PoolIndex.of(level, entity).count(request.key().item()) > 0;
			requeue(entity, request, now, !delivered);
			if (delivered) board.clearLatch(request.key(), "craftslow");
		}
	}

	/** Put a request back in play, optionally charging the failure that leads to a shortfall. */
	private void requeue(FakePlayerEntity entity, ItemRequest request, long now, boolean blame) {
		RequestStage from = request.stage();
		request.setStage(RequestStage.PENDING);
		if (blame) {
			request.noteFailure();
			request.setRetryAfter(now + BACKOFF_TICKS);
		} else {
			request.setRetryAfter(0L);
		}
		FakePlayerRequests.INSTANCE.fireStageChange(entity, request, from);
	}

	/** Drop a commission this Quartermaster has stopped waiting on, if it is still the one set. */
	private void cancelCommission(ServerLevel level, FakePlayerEntity entity, Craft craft) {
		if (!(level.getEntity(craft.crafter()) instanceof FakePlayerEntity crafter)) return;
		Commission commission = Commission.of(crafter.getAIState());
		if (commission == null || !commission.quartermaster().equals(entity.getUUID())) return;
		Commission.clear(crafter);
	}

	/**
	 * Whether the commissioned Crafter is still working on this. An unresolvable crafter is
	 * unobservable rather than gone, the same distinction assignmentFault makes, so it keeps its
	 * deadline rather than being written off the moment its chunk unloads.
	 */
	private boolean stillCrafting(ServerLevel level, FakePlayerEntity entity, Craft craft) {
		if (!(level.getEntity(craft.crafter()) instanceof FakePlayerEntity crafter) || !crafter.isAlive()) {
			return true;
		}
		if (!FPJobs.is(crafter.getAIState().jobId(), FPJobs.CRAFTER)) return false;
		Commission commission = Commission.of(crafter.getAIState());
		return commission != null && commission.quartermaster().equals(entity.getUUID());
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
			// a haul refusal is a persistent property of that runner's oversized state, not a
			// stock problem, so retrying it every 15 seconds only re-announces it forever
			if (HAUL_REFUSED.equals(request.shortfallReason())) continue;
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

	private void shortfall(ServerLevel level, FakePlayerEntity entity, ItemRequest request,
	                      @Nullable String liar, @Nullable String escalationReason, long now) {
		RequestStage from = request.stage();
		request.setStage(RequestStage.SHORTFALL);
		// counted, or prune can never drop it and a request from a dead requester re-dispatches forever
		request.noteFailure();
		// and backed off, or a consumer re-raising on a timer revives this every second and
		// re-resolves against the same empty pool, charging a failure each time
		request.setRetryAfter(now + BACKOFF_TICKS);
		// a lying resolver outranks a craft shortfall: it is a bug in something installed, and the
		// missing list it produced cannot be trusted either
		String reason = liar != null
				? "resolver " + liar + " reported stock it did not deposit"
				: escalationReason != null ? escalationReason
				: "cannot fill a request for " + request.key().item() + ", waiting for stock";
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
