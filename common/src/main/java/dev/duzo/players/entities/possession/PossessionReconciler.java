package dev.duzo.players.entities.possession;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * Compares a freshly loaded fake with the ledger on its first server tick. Chunks and the player file save on their own
 * schedules, so after a crash or an unload more than one copy of a fake can exist on disk, and only the one at the ledger's
 * epoch (or the pending state of a release the chunk never saw) may keep its items.
 */
public final class PossessionReconciler {
	private PossessionReconciler() {}

	/** Marks the fake reconciled, or leaves it unreconciled to try again, or discards it as stale. */
	public static void reconcile(FakePlayerEntity fake, ServerLevel level) {
		if (fake.reconcileOnCooldown() || level.getServer() == null) return;
		PossessionLedger ledger = PossessionLedger.of(level.getServer());
		if (!ledger.available()) { fake.delayReconcile(); return; }
		UUID id = fake.getUUID();
		PossessionLedger.Entry entry = ledger.get(id);
		long epoch = fake.possessionEpoch();

		if (entry == null) {
			if (fake.isVacant()) Constants.traceEvent("reconcile {} vacant shell with no ledger entry, leaving it vacant", id);
			fake.markReconciled();
			return;
		}
		if (epoch > entry.epoch && entry.sequence != 0) {
			fake.delayReconcile();
			return;
		}
		if (epoch > entry.epoch) {
			UUID possessor = fake.possessedBy() != null ? fake.possessedBy() : entry.possessor;
			Constants.traceEvent("reconcile {} copy epoch {} is ahead of ledger {}, healing the ledger", id, epoch, entry.epoch);
			if (!ledger.put(id, new PossessionLedger.Entry(epoch, possessor, fake.isVacant(), null))) {
				fake.delayReconcile();
				return;
			}
			fake.markReconciled();
			return;
		}
		if (entry.active) {
			reconcileActive(fake, ledger, entry, epoch);
			return;
		}
		if (entry.hasOutput() && entry.shell != null) {
			if (epoch != entry.epoch || fake.isVacant() || !entry.dimension.equals(level.dimension().location().toString())
					|| !PossessionManager.claimOutput(id, fake)) {
				discard(fake, entry, epoch, "not the canonical release output");
				return;
			}
			entry.markPendingAdopted();
			fake.markReconciled();
			return;
		}
		if (epoch == entry.epoch) {
			if (!fake.isVacant() && entry.pending != null) entry.markPendingAdopted();
			if (fake.isVacant()) Constants.traceEvent("reconcile {} vacant at the ledger's inactive epoch {}, leaving it vacant", id, epoch);
			else Constants.traceEvent("reconcile {} current at epoch {}", id, epoch);
			fake.markReconciled();
			return;
		}
		if (entry.pending != null && !entry.pendingAdopted()) {
			adopt(fake, ledger, entry);
			return;
		}
		discard(fake, entry, epoch, entry.pending != null ? "pending already adopted" : "no pending state");
	}

	private static void reconcileActive(FakePlayerEntity fake, PossessionLedger ledger, PossessionLedger.Entry entry, long epoch) {
		UUID id = fake.getUUID();
		if (epoch < entry.epoch) {
			discard(fake, entry, epoch, "its items are in the possessing player");
			return;
		}
		if (fake.isVacant()) {
			Constants.traceEvent("reconcile {} active shell at epoch {}, possessor {}", id, epoch, entry.possessor);
			PossessionManager.rebind(fake, entry.possessor);
			fake.markReconciled();
			return;
		}
		if (entry.sequence != 0) { fake.delayReconcile(); return; }
		Constants.traceEvent("reconcile {} not vacant at the ledger's active epoch {}, marking the possession over", id, epoch);
		if (!ledger.put(id, new PossessionLedger.Entry(epoch, entry.possessor, false, null))) {
			fake.delayReconcile();
			return;
		}
		fake.markReconciled();
	}

	private static void adopt(FakePlayerEntity fake, PossessionLedger ledger, PossessionLedger.Entry entry) {
		UUID id = fake.getUUID();
		CompoundTag pendingTag = entry.pending;
		Optional<FakeState> pending = pendingTag == null ? Optional.empty() : FakeState.fromTag(fake.registryAccess(), pendingTag);
		if (pending.isEmpty()) {
			Constants.LOG.error("The pending possession state of fake {} cannot be read; leaving this copy untouched", id);
			Constants.traceEvent("reconcile {} pending state unreadable, copy left inert", id);
			fake.delayReconcile();
			return;
		}
		long next = entry.epoch + 1;
		PossessionLedger.Entry adopted = new PossessionLedger.Entry(next, entry.possessor, false, pendingTag);
		adopted.markPendingAdopted();
		if (!ledger.put(id, adopted)) {
			fake.delayReconcile();
			return;
		}
		FakeState stale = FakeState.takeFrom(fake);
		int dropped = 0;
		for (ItemStack stack : stale.allStacks()) if (!stack.isEmpty()) dropped++;
		fake.clearVacant(next);
		pending.get().applyTo(fake);
		fake.markReconciled();
		Constants.traceEvent("reconcile {} adopted the pending state at epoch {} (replaced {} stale stacks)", id, next, dropped);
	}

	private static void discard(FakePlayerEntity fake, PossessionLedger.Entry entry, long epoch, String why) {
		Constants.traceEvent("reconcile {} discarding stale copy at epoch {} (ledger {} active={}): {}", fake.getUUID(), epoch, entry.epoch,
				entry.active, why);
		fake.discardStale();
	}
}
