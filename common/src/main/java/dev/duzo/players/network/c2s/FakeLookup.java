package dev.duzo.players.network.c2s;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The one way a packet handler turns an entity id into a fake. A vacant shell has its items and body with a possessing
 * player, and a copy that has not been checked against the ledger yet may be a stale one, so neither can be changed from
 * the client until that is settled.
 */
public final class FakeLookup {
	private FakeLookup() {}

	@Nullable
	public static FakePlayerEntity resolve(ServerPlayer sender, int id) {
		if (!(sender.level().getEntity(id) instanceof FakePlayerEntity fake)) return null;
		if (fake.isVacant() || fake.isUnreconciled()) {
			Constants.traceEvent("packet refused {}: fake {} is {}", sender.getUUID(), fake.getUUID(), fake.isVacant() ? "vacant" : "unreconciled");
			return null;
		}
		return fake;
	}
}
