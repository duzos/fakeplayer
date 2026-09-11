package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.api.requests.RequestKey;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.Job;
import dev.duzo.players.entities.ai.JobExecutor;
import dev.duzo.players.entities.ai.QuartermasterJobExecutor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Addressing by role and owner rather than by entity id, plus owner-only notification. */
@ApiStatus.Internal
public final class RequestRouting {
	/** How long an assignment may point at an unresolvable entity before it is written off. */
	public static final long ORPHAN_TICKS = 20L * 60L * 5L;

	private RequestRouting() {}

	/**
	 * The live board of a Quartermaster, or null if it has not ticked its job yet. A null board
	 * means <b>unknown</b>, never "gone": callers must wait rather than tear anything down.
	 */
	@Nullable
	public static RequestBoard boardOf(FakePlayerEntity quartermaster) {
		JobExecutor executor = quartermaster.activeJobExecutor();
		return executor instanceof QuartermasterJobExecutor qm ? qm.board() : null;
	}

	/**
	 * A read-only board view: the live board when the Quartermaster has ticked, otherwise the
	 * persisted copy from AIState. Correct in both cases, because a null executor means the live
	 * board has not been built yet and jobState is the last saved truth. Read only: mutating the
	 * returned snapshot changes nothing.
	 */
	public static RequestBoard snapshotOf(FakePlayerEntity quartermaster) {
		RequestBoard live = boardOf(quartermaster);
		return live != null ? live : RequestBoard.fromNbt(quartermaster.getAIState().jobState());
	}

	/**
	 * Same-owner fakes on the given job within requestRadius. A null owner returns nothing:
	 * treating it as a wildcard would let an unowned fake raid any player's pool.
	 */
	private static List<FakePlayerEntity> peers(ServerLevel level, Entity around, Job job, @Nullable UUID owner) {
		if (owner == null) return List.of();
		AABB box = around.getBoundingBox().inflate(PlayersConfig.get().requestRadius);
		return level.getEntitiesOfClass(FakePlayerEntity.class, box, fake -> fake.isAlive()
				&& fake != around
				&& fake.getAIState().job() == job
				&& fake.getAIState().running()
				&& owner.equals(fake.getAIState().ownerUUID()));
	}

	/** Quartermasters with a marked pool that could serve this owner, nearest first. */
	public static List<FakePlayerEntity> quartermastersFor(ServerLevel level, Entity around, @Nullable UUID owner) {
		List<FakePlayerEntity> found = new ArrayList<>(peers(level, around, Job.QUARTERMASTER, owner));
		int beforePool = found.size();
		found.removeIf(qm -> StoragePool.read(qm.getAIState()).isEmpty());
		if (dev.duzo.players.platform.Services.PLATFORM.isDevelopmentEnvironment()
				&& found.isEmpty() && around instanceof FakePlayerEntity asker) {
			// enumerate every quartermaster in the level and say why each was excluded
			StringBuilder why = new StringBuilder();
			for (Entity e : level.getAllEntities()) {
				if (!(e instanceof FakePlayerEntity fake)) continue;
				if (fake.getAIState().job() != Job.QUARTERMASTER) continue;
				why.append(" [").append(fake.getUUID().toString(), 0, 8)
						.append(" owner=").append(fake.getAIState().ownerUUID())
						.append(" running=").append(fake.getAIState().running())
						.append(" dist=").append(String.format("%.1f", Math.sqrt(fake.distanceToSqr(around))))
						.append(" pool=").append(StoragePool.read(fake.getAIState()).size())
						.append("]");
			}
			RequestDebug.state(asker, "qmscan", "want-owner={} radius={} inRadius={} afterPoolFilter={} candidates:{}",
					owner, PlayersConfig.get().requestRadius, beforePool, found.size(),
					why.length() == 0 ? " NONE IN LEVEL" : why);
		}
		found.sort(Comparator.comparingDouble(qm -> qm.distanceToSqr(around)));
		return found;
	}

	/**
	 * Nearest Quartermaster that actually has the item in stock, falling back to the nearest with a
	 * marked pool. Testing only that a pool is marked let an empty storeroom shadow a stocked one
	 * and report a false shortfall.
	 */
	@Nullable
	public static FakePlayerEntity nearestCapable(ServerLevel level, Entity requester, @Nullable UUID owner, ResourceLocation item) {
		List<FakePlayerEntity> found = quartermastersFor(level, requester, owner);
		if (dev.duzo.players.platform.Services.PLATFORM.isDevelopmentEnvironment()
				&& !found.isEmpty() && requester instanceof FakePlayerEntity asker) {
			StringBuilder why = new StringBuilder();
			for (FakePlayerEntity qm : found) {
				why.append(" [").append(qm.getUUID().toString(), 0, 8)
						.append(" board=").append(boardOf(qm) == null ? "NOT-TICKED" : "live")
						.append(" stock=").append(boardOf(qm) == null ? "?"
								: String.valueOf(PoolIndex.of((ServerLevel) qm.level(), qm).count(item)))
						.append("]");
			}
			RequestDebug.state(asker, "capable", "{} candidates:{}", item, why);
		}
		for (FakePlayerEntity qm : found) {
			if (boardOf(qm) == null) continue;
			if (PoolIndex.of((ServerLevel) qm.level(), qm).count(item) > 0) return qm;
		}
		for (FakePlayerEntity qm : found) {
			if (boardOf(qm) != null) return qm;
		}
		return null;
	}

	private record OwnerScan(UUID owner, long tick) {}

	// getAllEntities walks every loaded entity in a level and this scans every level, while
	// holderOf is reached from raise(), cancel() and an unthrottled packet. Memoized for the tick
	// it was built on: server-thread only, and one entry is enough because callers ask about one
	// owner at a time. UUIDs rather than entities, so a quit-to-title cannot leave a whole
	// ServerLevel strongly reachable from a static field.
	@Nullable private static OwnerScan lastScanKey;
	private static List<UUID> lastScan = List.of();

	/**
	 * Every loaded Quartermaster belonging to the owner, in <b>every</b> level, regardless of
	 * distance.
	 *
	 * <p>All levels, not just the requester's: a player's request key carries no dimension, so a
	 * per-level scan let the same ask go live on one board in the Nether and another in the
	 * Overworld and be delivered twice.
	 */
	public static List<FakePlayerEntity> allQuartermastersOf(ServerLevel level, @Nullable UUID owner) {
		if (owner == null) return List.of();
		MinecraftServer server = level.getServer();
		OwnerScan want = new OwnerScan(owner, level.getGameTime());
		if (!want.equals(lastScanKey)) {
			List<UUID> found = new ArrayList<>();
			for (ServerLevel each : server.getAllLevels()) {
				for (Entity entity : each.getAllEntities()) {
					if (!(entity instanceof FakePlayerEntity fake)) continue;
					if (fake.getAIState().job() != Job.QUARTERMASTER) continue;
					if (!owner.equals(fake.getAIState().ownerUUID())) continue;
					found.add(fake.getUUID());
				}
			}
			lastScanKey = want;
			lastScan = List.copyOf(found);
		}

		// re-resolved every call: the memo may outlive an entity that died or unloaded later in
		// this same tick, and reviving a board that is about to be discarded loses the request
		List<FakePlayerEntity> live = new ArrayList<>(lastScan.size());
		for (UUID id : lastScan) {
			for (ServerLevel each : server.getAllLevels()) {
				if (each.getEntity(id) instanceof FakePlayerEntity fake && fake.isAlive() && !fake.isRemoved()) {
					live.add(fake);
					break;
				}
			}
		}
		return live;
	}

	/**
	 * The Quartermaster already holding a request with this key, or null.
	 *
	 * <p>Deliberately scanned level-wide and owner-scoped rather than within requestRadius: a
	 * radius scan around the requester loses sight of the holder as soon as the requester walks
	 * away, and the same ask then goes live on a second board and is delivered twice.
	 *
	 * <p>Uses the snapshot, so a Quartermaster that has not ticked since a reload is still seen.
	 */
	@Nullable
	public static FakePlayerEntity holderOf(ServerLevel level, Entity requester, @Nullable UUID owner, RequestKey key) {
		// the holder is almost always near the requester, and that scan is section-indexed and
		// bounded, so try it before walking every entity in every level
		FakePlayerEntity near = findHolder(quartermastersFor(level, requester, owner), key);
		if (near != null) return near;
		return findHolder(allQuartermastersOf(level, owner), key);
	}

	@Nullable
	private static FakePlayerEntity findHolder(List<FakePlayerEntity> candidates, RequestKey key) {
		for (FakePlayerEntity qm : candidates) {
			// any stage, not just open: a SHORTFALL copy is still this key's home, and matching only
			// open ones let a re-raise start a second copy on a different board
			if (snapshotOf(qm).find(key) != null) return qm;
		}
		return null;
	}

	/**
	 * Nearest same-owner Runner with no Haul. Busy-ness is read from AIState, so a Runner that has
	 * not ticked since a reload still reads as busy and cannot be double-assigned.
	 */
	@Nullable
	public static FakePlayerEntity nearestFreeRunner(ServerLevel level, FakePlayerEntity quartermaster) {
		FakePlayerEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (FakePlayerEntity fake : peers(level, quartermaster, Job.RUNNER, quartermaster.getAIState().ownerUUID())) {
			if (Haul.isBusy(fake)) continue;
			double d = fake.distanceToSqr(quartermaster);
			if (d < bestDist) {
				bestDist = d;
				best = fake;
			}
		}
		return best;
	}

	/** Why an assignment is no longer valid, or null while it is fine or merely unobservable. */
	public enum AssignmentFault { RE_JOBBED, ORPHANED }

	/**
	 * Checks an assignment without ever consulting an executor. An assignee that does not resolve is
	 * unobservable (unloaded chunk, other dimension), not dead, so it is left alone until
	 * {@link #ORPHAN_TICKS} have passed.
	 */
	@Nullable
	public static AssignmentFault assignmentFault(ServerLevel level, UUID quartermaster, ItemRequest request, long now) {
		UUID assignee = request.assignee();
		if (assignee == null) return AssignmentFault.RE_JOBBED;
		if (!(level.getEntity(assignee) instanceof FakePlayerEntity runner) || !runner.isAlive()) {
			return now - request.assignedAt() > ORPHAN_TICKS ? AssignmentFault.ORPHANED : null;
		}
		// running() is AIState too, and a stopped runner never ticks, so it can neither finish the
		// delivery nor release its own Haul. Without this the request and the cargo strand forever.
		// running() covers stopped, which strands the dispatch because a stopped runner never ticks
		// again on its own. jobPaused is deliberately NOT checked: it is true whenever the owner
		// merely has the fake's menu open, and treating that as a fault would requeue the request
		// and charge a failure for an everyday UI action. A pause ends by itself.
		if (runner.getAIState().job() != Job.RUNNER || !runner.getAIState().running()) {
			return AssignmentFault.RE_JOBBED;
		}
		Haul haul = Haul.of(runner.getAIState());
		if (haul == null || !haul.quartermaster().equals(quartermaster)) return AssignmentFault.RE_JOBBED;
		return null;
	}

	/**
	 * Tell the owner and nobody else, attributed to the fake the message is about. Returns false
	 * when nothing was delivered, so a caller can decline to burn its once-only latch on a message
	 * an offline owner never saw.
	 */
	public static boolean notifyOwner(ServerLevel level, FakePlayerEntity about, String message) {
		UUID owner = about.getAIState().ownerUUID();
		if (owner == null) return false;
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
		if (player == null) return false;
		player.sendSystemMessage(Component.literal(about.getName().getString() + ": " + message));
		return true;
	}
}
