package dev.duzo.players.api.requests;

import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.Job;
import dev.duzo.players.entities.ai.JobHelpers;
import dev.duzo.players.entities.ai.requests.PoolIndex;
import dev.duzo.players.entities.ai.requests.RequestBoard;
import dev.duzo.players.entities.ai.requests.RequestRouting;
import dev.duzo.players.entities.ai.requests.StoragePool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The whole public surface of the request system. Addons need nothing else.
 *
 * <p><b>Being a consumer costs one call.</b> There is no interface to implement: any code holding a
 * {@link FakePlayerEntity} or a {@link ServerPlayer} calls {@link #raise} and gets a
 * {@link RaisedRequest} back, carrying the Quartermaster that took it. Keep that handle, or re-find
 * the request with {@link #findOpen}.
 *
 * <p><b>Raising is not free.</b> It scans for a Quartermaster, so throttle it rather than calling it
 * every tick. Re-raising the same key returns {@link RaiseResult#ALREADY_OPEN}, topping the existing
 * request up if the new ask is larger.
 *
 * <p>Two extension points:
 * <ul>
 *   <li>{@link Resolver} appends to the stock, then craft, then report chain.
 *   <li>{@link Listener} observes the full lifecycle, which is what a fleet dashboard needs.
 * </ul>
 *
 * <p>Known limits, both deliberate: {@link Job} is a positional enum, so an addon can replace an
 * existing job's behaviour through {@code JobExecutors.register} but cannot add a new job. And a
 * request must be raised on behalf of a fake or a real player; there is no block-entity requester.
 */
public final class FakePlayerRequests {
	/** Registration entry point. */
	public static final FakePlayerRequests INSTANCE = new FakePlayerRequests();

	/** Default priority for a request raised by a fake player. */
	public static final int PRIORITY_FAKE = 0;
	/** Default priority for a request raised by a real player. Outranks fake requests. */
	public static final int PRIORITY_PLAYER = 10;

	/**
	 * One step in the stock, then craft, then report chain.
	 *
	 * <p><b>This is effectful.</b> An implementation must synchronously move up to
	 * {@code request.remaining()} units of the requested item into one of the Quartermaster's pool
	 * containers and return how many it actually placed. {@link #deposit} does that correctly;
	 * prefer it over writing to the containers yourself. The Runner collects from the pool and from
	 * nowhere else.
	 *
	 * <p>The engine measures the pool index before and after your call and compares the delta
	 * against your return value. Report more than you deposited and you are named in the owner's
	 * shortfall message, so a broken resolver is visible rather than silently looping.
	 *
	 * <p>Called at most once per resolve pass per pending request, on the server thread, while the
	 * Quartermaster is ticking. Do not block. May be called again for a partially filled request, so
	 * treat {@code request.remaining()} as the current outstanding amount, not the original.
	 */
	public interface Resolver {
		/** @return units of the request's item actually placed into the pool. Zero if none. */
		int deposit(FakePlayerEntity quartermaster, ItemRequest request);

		/** Short name, used in the shortfall message shown to the owner. */
		default String name() {
			return this.getClass().getSimpleName();
		}
	}

	/** Notified across the whole request lifecycle. */
	public interface Listener {
		/** A request just went onto a board. */
		default void onRaised(FakePlayerEntity quartermaster, ItemRequest request) {}

		/** A request changed stage. {@code from} is the previous stage. */
		default void onStageChange(FakePlayerEntity quartermaster, ItemRequest request, RequestStage from) {}

		/** A request left the board, whether delivered, cancelled, replaced or pruned. */
		default void onRemoved(FakePlayerEntity quartermaster, ItemRequest request) {}
	}

	private final List<Resolver> resolvers = new ArrayList<>();
	private final List<Listener> listeners = new ArrayList<>();

	private FakePlayerRequests() {}

	/** Appends a resolver to the escalation chain. Call during mod init. */
	public void addResolver(Resolver resolver) {
		this.resolvers.add(resolver);
	}

	/** Appends a lifecycle listener. Call during mod init. */
	public void addListener(Listener listener) {
		this.listeners.add(listener);
	}

	/** A copy, so one addon cannot clear another's resolvers. */
	@ApiStatus.Internal
	public List<Resolver> resolverChain() {
		return List.copyOf(this.resolvers);
	}

	@ApiStatus.Internal
	public void fireRaised(FakePlayerEntity qm, ItemRequest request) {
		for (Listener l : this.listeners) l.onRaised(qm, request);
	}

	@ApiStatus.Internal
	public void fireStageChange(FakePlayerEntity qm, ItemRequest request, RequestStage from) {
		if (from == request.stage()) return;
		for (Listener l : this.listeners) l.onStageChange(qm, request, from);
	}

	@ApiStatus.Internal
	public void fireRemoved(FakePlayerEntity qm, ItemRequest request) {
		for (Listener l : this.listeners) l.onRemoved(qm, request);
	}

	/**
	 * Raise on behalf of a fake player, delivered into its inventory.
	 *
	 * <p>Never returns null. {@link RaisedRequest#request()} is non-null exactly when
	 * {@link RaiseResult#hasRequest()} is true, which excludes
	 * {@link RaiseResult#HOLDER_NOT_READY} (a holder that has not ticked since a reload).
	 */
	public static RaisedRequest raise(FakePlayerEntity requester, ItemStack want, int priority) {
		if (want.isEmpty() || !(requester.level() instanceof ServerLevel level)) {
			return RaisedRequest.failed(RaiseResult.INVALID);
		}
		UUID owner = requester.getAIState().ownerUUID();
		if (owner == null) return RaisedRequest.failed(RaiseResult.INVALID);
		RequestKey key = new RequestKey(requester.getUUID(), RequesterKind.FAKE,
				requester.getAIState().job(), BuiltInRegistries.ITEM.getKey(want.getItem()));
		return post(level, requester, owner, key, want.getCount(), priority);
	}

	/** Raise on behalf of a real player, delivered wherever they stand when the Runner arrives. */
	public static RaisedRequest raise(ServerPlayer requester, ItemStack want, int priority) {
		if (want.isEmpty() || !(requester.level() instanceof ServerLevel level)) {
			return RaisedRequest.failed(RaiseResult.INVALID);
		}
		RequestKey key = new RequestKey(requester.getUUID(), RequesterKind.PLAYER,
				Job.NONE, BuiltInRegistries.ITEM.getKey(want.getItem()));
		return post(level, requester, requester.getUUID(), key, want.getCount(), priority);
	}

	private static RaisedRequest post(ServerLevel level, Entity requester, UUID owner,
	                                  RequestKey key, int count, int priority) {
		// dedupe across every reachable board, using the persisted snapshot so a Quartermaster that
		// has not ticked since a reload is still seen: otherwise two boards serve one ask and the
		// pool is drawn twice
		// this key's home board, at any stage: a shortfalled copy is still its home, and matching
		// only open ones let a re-raise start a second copy on another board
		FakePlayerEntity holder = RequestRouting.holderOf(level, owner, key);
		if (holder != null) {
			RequestBoard board = RequestRouting.boardOf(holder);
			ItemRequest existing = board == null ? null : board.find(key);
			if (existing != null) {
				RequestStage from = existing.stage();
				board.revive(existing, count, priority);
				INSTANCE.fireStageChange(holder, existing, from);
				return new RaisedRequest(RaiseResult.ALREADY_OPEN, holder, existing);
			}
			// known holder that has not ticked, so it cannot be revived this tick. A distinct
			// result, because callers must not dereference request() here.
			return new RaisedRequest(RaiseResult.HOLDER_NOT_READY, holder, null);
		}

		FakePlayerEntity quartermaster = RequestRouting.nearestCapable(level, requester, owner, key.item());
		if (quartermaster == null) return RaisedRequest.failed(RaiseResult.NO_QUARTERMASTER);
		RequestBoard board = RequestRouting.boardOf(quartermaster);
		if (board == null) return RaisedRequest.failed(RaiseResult.NO_QUARTERMASTER);

		ItemRequest request = board.post(
				new ItemRequest(key, count, priority, level.getGameTime()),
				PlayersConfig.get().requestMaxPerQuartermaster);
		if (request == null) return RaisedRequest.failed(RaiseResult.BOARD_FULL);
		INSTANCE.fireRaised(quartermaster, request);
		return new RaisedRequest(RaiseResult.RAISED, quartermaster, request);
	}

	/** The open request with this key on any reachable board, or null. */
	@Nullable
	public static RaisedRequest findOpen(ServerLevel level, UUID owner, RequestKey key) {
		FakePlayerEntity holder = RequestRouting.holderOf(level, owner, key);
		if (holder == null) return null;
		RequestBoard board = RequestRouting.boardOf(holder);
		ItemRequest open = board == null ? null : board.findOpen(key);
		return new RaisedRequest(open == null ? RaiseResult.HOLDER_NOT_READY : RaiseResult.ALREADY_OPEN, holder, open);
	}

	/**
	 * Cancel a request on a known Quartermaster. An assigned Runner notices on its next tick, puts
	 * the goods back in the pool and goes free, so cancelling never strands cargo.
	 */
	public static boolean cancel(FakePlayerEntity quartermaster, RequestKey key) {
		RequestBoard board = RequestRouting.boardOf(quartermaster);
		if (board == null) return false;
		ItemRequest existing = board.find(key);
		if (existing == null) return false;
		board.forget(existing);
		INSTANCE.fireRemoved(quartermaster, existing);
		return true;
	}

	/** Cancel by key wherever it is open. */
	public static boolean cancel(ServerLevel level, UUID owner, RequestKey key) {
		FakePlayerEntity holder = RequestRouting.holderOf(level, owner, key);
		return holder != null && cancel(holder, key);
	}

	/**
	 * A Quartermaster's open requests. Empty both when there is nothing outstanding and when the
	 * Quartermaster has not ticked yet, so check {@link #hasLiveBoard} to tell those apart.
	 */
	public static List<ItemRequest> outstanding(FakePlayerEntity quartermaster) {
		RequestBoard board = RequestRouting.boardOf(quartermaster);
		return board == null ? List.of() : board.open();
	}

	/**
	 * Every request on a Quartermaster's board including terminal ones, which is what a dashboard
	 * needs: {@link #outstanding} deliberately omits shortfalls, and those are the interesting rows.
	 * Empty when the Quartermaster has not ticked; see {@link #hasLiveBoard}.
	 */
	public static List<ItemRequest> allRequests(FakePlayerEntity quartermaster) {
		RequestBoard board = RequestRouting.boardOf(quartermaster);
		return board == null ? List.of() : board.all();
	}

	/** Whether this Quartermaster has ticked its job and so has a live board to read. */
	public static boolean hasLiveBoard(FakePlayerEntity quartermaster) {
		return RequestRouting.boardOf(quartermaster) != null;
	}

	/** Every Quartermaster near this entity that could serve the given owner, nearest first. */
	public static List<FakePlayerEntity> quartermasters(ServerLevel level, Entity near, UUID owner) {
		return RequestRouting.quartermastersFor(level, near, owner);
	}

	/** Every loaded Quartermaster in the level belonging to this owner, for a base-wide dashboard. */
	public static List<FakePlayerEntity> quartermasters(ServerLevel level, UUID owner) {
		List<FakePlayerEntity> found = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof FakePlayerEntity fake)) continue;
			if (fake.getAIState().job() != Job.QUARTERMASTER) continue;
			if (!owner.equals(fake.getAIState().ownerUUID())) continue;
			found.add(fake);
		}
		return found;
	}

	/** The containers making up a Quartermaster's pool. */
	public static List<BlockPos> poolOf(FakePlayerEntity quartermaster) {
		return StoragePool.read(quartermaster.getAIState());
	}

	/** How many of an item a Quartermaster's pool currently holds. */
	public static int stock(ServerLevel level, FakePlayerEntity quartermaster, ResourceLocation item) {
		return PoolIndex.of(level, quartermaster).count(item);
	}

	/**
	 * Take up to {@code count} of an item out of a Quartermaster's pool, for a {@link Resolver} that
	 * needs to consume ingredients. Returns what it actually removed, which may be less than asked.
	 *
	 * <p><b>Ownership transfers to you.</b> The stacks are removed from the pool before this
	 * returns, and nothing puts them back: if your craft then fails you must {@link #deposit} them
	 * again or they are destroyed.
	 *
	 * <p>The counterpart to {@link #deposit}. Without it a crafting resolver would have to reach
	 * outside this package for the container lookup, which is exactly what these two helpers exist
	 * to avoid.
	 */
	public static List<ItemStack> withdraw(ServerLevel level, FakePlayerEntity quartermaster, ResourceLocation item, int count) {
		List<ItemStack> taken = new ArrayList<>();
		int owed = Math.max(0, count);
		for (BlockPos pos : poolOf(quartermaster)) {
			if (owed <= 0) break;
			Container container = JobHelpers.containerAt(level, pos);
			if (container == null) continue;
			// as in PoolIndex.rebuild: a pooled chest may since have become a furnace, whose fuel
			// and output slots are not storage
			if (container instanceof net.minecraft.world.WorldlyContainer) continue;
			for (int slot = 0; slot < container.getContainerSize() && owed > 0; slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty()) continue;
				if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item)) continue;
				ItemStack split = stack.split(Math.min(owed, stack.getCount()));
				owed -= split.getCount();
				taken.add(split);
				if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
				container.setChanged();
			}
		}
		if (!taken.isEmpty()) PoolIndex.markDirty(level, quartermaster.getUUID());
		return taken;
	}

	/**
	 * Put a stack into a Quartermaster's pool and return how many units landed. This is what a
	 * {@link Resolver} should call: writing to the containers directly means re-deriving the lookup
	 * and rediscovering that {@code HopperBlockEntity.addItem} mutates its argument and, on the
	 * partial-merge branch, returns the same object.
	 */
	public static int deposit(ServerLevel level, FakePlayerEntity quartermaster, ItemStack stack) {
		if (stack.isEmpty()) return 0;
		int placed = 0;
		for (BlockPos pos : poolOf(quartermaster)) {
			if (stack.isEmpty()) break;
			Container container = JobHelpers.containerAt(level, pos);
			if (container == null) continue;
			int before = stack.getCount();
			ItemStack leftover = HopperBlockEntity.addItem(null, container, stack.copy(), null);
			int moved = before - leftover.getCount();
			if (moved <= 0) continue;
			placed += moved;
			stack.shrink(moved);
			container.setChanged();
		}
		if (placed > 0) PoolIndex.markDirty(level, quartermaster.getUUID());
		return placed;
	}
}
