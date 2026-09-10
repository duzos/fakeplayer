package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.api.requests.RequestKey;
import dev.duzo.players.api.requests.RequestStage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One Quartermaster's queue, and the single authority for what is owed and who is assigned.
 *
 * <p>Ordered by priority descending then raise time ascending, so an urgent request wins the next
 * free Runner without preempting one mid-delivery.
 */
@ApiStatus.Internal
public final class RequestBoard {
	private static final Comparator<ItemRequest> ORDER =
			Comparator.<ItemRequest>comparingInt(r -> -r.priority()).thenComparingLong(ItemRequest::raisedAt);

	private final List<ItemRequest> requests = new ArrayList<>();
	// deliberately not persisted: transient notification bookkeeping, and the board shares a
	// synced string with a hard character cap that latch strings would blow through
	private final Set<String> latched = new HashSet<>();

	@Nullable
	public ItemRequest findOpen(RequestKey key) {
		ItemRequest existing = find(key);
		return existing != null && existing.isOpen() ? existing : null;
	}

	/**
	 * Adds the request, or revives and tops up an existing one with the same key, or returns null
	 * when the board is at capacity. The cap counts every request, not just open ones, because
	 * shortfalls persist too.
	 */
	@Nullable
	public ItemRequest post(ItemRequest request, int cap) {
		ItemRequest existing = find(request.key());
		if (existing != null) {
			revive(existing, request.wanted(), request.priority());
			return existing;
		}
		if (requests.size() >= cap) return null;
		requests.add(request);
		requests.sort(ORDER);
		return request;
	}

	/**
	 * Top up an existing request and put it back in play if it had gone terminal.
	 *
	 * <p>Revived in place rather than replaced: forget+add would wipe this key's latches and its
	 * lifetime failure count, so a consumer re-raising on a timer would re-notify the owner on
	 * every raise and could never be pruned.
	 */
	public void revive(ItemRequest existing, int wanted, int priority) {
		existing.topUp(wanted, priority);
		if (!existing.isOpen()) {
			existing.setStage(RequestStage.PENDING);
			existing.resetFailures();
			existing.setRetryAfter(0L);
		}
		requests.sort(ORDER);
	}

	@Nullable
	public ItemRequest find(RequestKey key) {
		for (ItemRequest r : requests) {
			if (r.key().equals(key)) return r;
		}
		return null;
	}

	/** The request assigned to this Runner, or null. The Runner's whole task, derived not stored. */
	@Nullable
	public ItemRequest assignedTo(UUID runner) {
		for (ItemRequest r : requests) {
			if (r.stage() == RequestStage.DISPATCHED && runner.equals(r.assignee())) return r;
		}
		return null;
	}

	public List<ItemRequest> all() {
		return List.copyOf(requests);
	}

	public List<ItemRequest> open() {
		return requests.stream().filter(ItemRequest::isOpen).toList();
	}

	public int openCount() {
		return (int) requests.stream().filter(ItemRequest::isOpen).count();
	}

	/** Highest-priority pending request whose backoff has elapsed, or null. */
	@Nullable
	public ItemRequest nextPending(long now) {
		for (ItemRequest r : requests) {
			if (r.stage() == RequestStage.PENDING && now >= r.retryAfter()) return r;
		}
		return null;
	}

	public List<ItemRequest> dispatched() {
		return requests.stream().filter(r -> r.stage() == RequestStage.DISPATCHED).toList();
	}

	public void forget(ItemRequest request) {
		requests.remove(request);
		clearLatch(request.key());
	}

	/**
	 * Drops requests whose requester is gone and which have failed repeatedly. Open requests are
	 * eligible too: a pending request for a dead fake is the one that costs real work, because it
	 * keeps being dispatched.
	 */
	public void prune(Predicate<RequestKey> requesterGone, int minFailures, long raisedBefore,
	                  Consumer<ItemRequest> onRemoved) {
		requests.removeIf(r -> {
			boolean drop = r.lifetimeFailures() >= minFailures
					&& r.raisedAt() <= raisedBefore
					&& requesterGone.test(r.key());
			if (drop) {
				clearLatch(r.key());
				onRemoved.accept(r);
			}
			return drop;
		});
	}

	/** True the first time this (requester, item, reason) triple is seen; false while it stays latched. */
	public boolean latch(RequestKey key, String reason) {
		return latched.add(latchKey(key, reason));
	}

	public boolean isLatched(RequestKey key, String reason) {
		return latched.contains(latchKey(key, reason));
	}

	/** Clears one reason only. Clearing by prefix let a shortfall clear a routing alert and re-armed spam. */
	public void clearLatch(RequestKey key, String reason) {
		latched.remove(latchKey(key, reason));
	}

	/** Clears every latch for a key. Only correct when the request itself is finished or gone. */
	public void clearLatch(RequestKey key) {
		String prefix = key.requester() + "|" + key.item() + "|";
		latched.removeIf(entry -> entry.startsWith(prefix));
	}

	private static String latchKey(RequestKey key, String reason) {
		return key.requester() + "|" + key.item() + "|" + reason;
	}

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		ListTag list = new ListTag();
		for (ItemRequest r : requests) {
			if (r.isOpen() || r.stage() == RequestStage.SHORTFALL) list.add(r.toNbt());
		}
		tag.put("Requests", list);
		return tag;
	}

	/**
	 * DISPATCHED and its assignee are preserved deliberately. That is safe because the Runner
	 * stores no dispatch state of its own: on load it reads its Haul, finds this assignment, and
	 * continues, so there is no second copy of the claim to disagree with.
	 */
	public static RequestBoard fromNbt(CompoundTag tag) {
		RequestBoard board = new RequestBoard();
		ListTag list = tag.getListOrEmpty("Requests");
		for (int i = 0; i < list.size(); i++) {
			ItemRequest r = ItemRequest.fromNbt(list.getCompoundOrEmpty(i));
			if (r != null) board.requests.add(r);
		}
		board.requests.sort(ORDER);
		return board;
	}
}
