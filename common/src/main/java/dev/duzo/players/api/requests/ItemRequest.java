package dev.duzo.players.api.requests;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One outstanding ask. {@code wanted} is the size asked for and {@code remaining} is what is still
 * owed, so a partial fill and a future craft-the-gap escalation both work by decrementing rather
 * than by replacing the request.
 *
 * <p>Read freely. The mutators are engine-internal: driving them from an addon corrupts the
 * dispatch state machine. Use {@link FakePlayerRequests} instead.
 */
public final class ItemRequest {
	/** Upper bound on a single ask, so an addon cannot queue unbounded work. */
	public static final int MAX_COUNT = 1024;

	private final RequestKey key;
	private final long raisedAt;
	private int wanted;
	private int remaining;
	private int priority;
	private RequestStage stage = RequestStage.PENDING;
	@Nullable private UUID assignee;
	private long assignedAt;
	@Nullable private String shortfallReason;
	private int failures;
	private int lifetimeFailures;
	private long retryAfter;

	@ApiStatus.Internal
	public ItemRequest(RequestKey key, int wanted, int priority, long raisedAt) {
		this.key = key;
		this.wanted = clampCount(wanted);
		this.remaining = this.wanted;
		this.priority = priority;
		this.raisedAt = raisedAt;
	}

	private static int clampCount(int count) {
		return Math.max(1, Math.min(MAX_COUNT, count));
	}

	public RequestKey key() { return key; }
	public int wanted() { return wanted; }
	public int remaining() { return remaining; }
	public int priority() { return priority; }
	public long raisedAt() { return raisedAt; }
	public RequestStage stage() { return stage; }
	@Nullable public UUID assignee() { return assignee; }
	public long assignedAt() { return assignedAt; }
	@Nullable public String shortfallReason() { return shortfallReason; }
	public int failures() { return failures; }
	public long retryAfter() { return retryAfter; }

	/** Failures over this request's whole life. Never reset, so pruning can rely on it. */
	public int lifetimeFailures() { return lifetimeFailures; }

	/** Open means a Quartermaster still owes this. Terminal stages are not open. */
	public boolean isOpen() {
		return stage == RequestStage.PENDING || stage == RequestStage.DISPATCHED;
	}

	@ApiStatus.Internal public void setRemaining(int remaining) { this.remaining = Math.max(0, remaining); }
	@ApiStatus.Internal public void setStage(RequestStage stage) { this.stage = stage; }
	@ApiStatus.Internal public void setShortfallReason(@Nullable String reason) { this.shortfallReason = reason; }
	@ApiStatus.Internal public void setRetryAfter(long tick) { this.retryAfter = tick; }

	@ApiStatus.Internal
	public void noteFailure() {
		this.failures++;
		this.lifetimeFailures++;
	}

	/** Clears the backoff counter only. The lifetime counter is what pruning uses. */
	@ApiStatus.Internal public void resetFailures() { this.failures = 0; }

	@ApiStatus.Internal
	public void assignTo(@Nullable UUID runner, long now) {
		this.assignee = runner;
		this.assignedAt = runner == null ? 0L : now;
	}

	/**
	 * Raise the ask when the same key is re-raised for more than is outstanding.
	 *
	 * <p>The ceiling is the largest single ask, not that ask plus whatever already arrived:
	 * comparing against {@code remaining} alone turned "64, then 64 again" into 94 delivered once
	 * 30 of the first ask had landed.
	 */
	@ApiStatus.Internal
	public void topUp(int newWanted, int newPriority) {
		int target = clampCount(newWanted);
		int delivered = wanted - remaining;
		int outstanding = Math.max(0, target - delivered);
		if (outstanding > remaining) {
			remaining = outstanding;
			wanted = Math.max(wanted, target);
		}
		priority = Math.max(priority, newPriority);
	}

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		tag.put("Key", key.toNbt());
		tag.putInt("Wanted", wanted);
		tag.putInt("Remaining", remaining);
		tag.putInt("Priority", priority);
		tag.putLong("RaisedAt", raisedAt);
		tag.putString("Stage", stage.name());
		tag.putInt("LifeFail", lifetimeFailures);
		if (assignee != null) {
			tag.putIntArray("Assignee", UUIDUtil.uuidToIntArray(assignee));
			tag.putLong("AssignedAt", assignedAt);
		}
		// retryAfter, failures and shortfallReason are deliberately not persisted: transient
		// scheduling detail, and the board shares a synced string with a hard size cap
		return tag;
	}

	@Nullable
	public static ItemRequest fromNbt(CompoundTag tag) {
		RequestKey key = RequestKey.fromNbt(tag.getCompound("Key"));
		if (key == null) return null;
		int wanted = tag.contains("Wanted") ? tag.getInt("Wanted") : 1;
		int priority = tag.contains("Priority") ? tag.getInt("Priority") : 0;
		long raisedAt = tag.contains("RaisedAt") ? tag.getLong("RaisedAt") : 0L;
		ItemRequest req = new ItemRequest(key, wanted, priority, raisedAt);
		// clamped to wanted: topUp derives delivered as wanted - remaining, and a hand-edited or
		// truncated board could otherwise make that negative and inflate the ask
		int remaining = tag.contains("Remaining") ? tag.getInt("Remaining") : req.wanted;
		req.remaining = Math.min(req.wanted, Math.max(0, remaining));
		String stageName = tag.contains("Stage") ? tag.getString("Stage") : RequestStage.PENDING.name();
		req.stage = RequestStage.byName(stageName, RequestStage.PENDING);
		req.lifetimeFailures = tag.contains("LifeFail") ? tag.getInt("LifeFail") : 0;
		int[] raw = tag.contains("Assignee") ? tag.getIntArray("Assignee") : null;
		if (raw != null && raw.length == 4) {
			req.assignee = UUIDUtil.uuidFromIntArray(raw);
			req.assignedAt = tag.contains("AssignedAt") ? tag.getLong("AssignedAt") : 0L;
		}
		return req;
	}
}
