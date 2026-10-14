package dev.duzo.players.entities.ai.claims;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leased claims on block regions so two fakes do not work the same ground. In memory only: a restart drops
 * every claim, and a holder that stops renewing loses its claim when the lease runs out.
 */
public final class WorkClaims {
	public record Claim(UUID holder, String scope, ResourceKey<Level> dim, BoundingBox box, long expiry) {}

	private static final Map<UUID, List<Claim>> CLAIMS = new ConcurrentHashMap<>();

	private WorkClaims() {}

	/** Claims `box` unless another holder has a live claim intersecting it, in any scope. Re-claiming the same box renews it. */
	public static synchronized boolean tryClaim(ServerLevel level, UUID holder, String scope, BoundingBox box, int ttl) {
		long now = level.getGameTime();
		prune(now);
		for (Claim c : overlapping(level, box)) {
			if (!c.holder().equals(holder)) return false;
		}
		List<Claim> mine = CLAIMS.computeIfAbsent(holder, k -> new ArrayList<>());
		mine.removeIf(c -> c.dim().equals(level.dimension()) && c.box().equals(box));
		mine.add(new Claim(holder, scope, level.dimension(), box, now + ttl));
		return true;
	}

	/** Extends the lease on a claim this holder still has. False means it lapsed or was never held. */
	public static synchronized boolean renew(ServerLevel level, UUID holder, BoundingBox box, int ttl) {
		long now = level.getGameTime();
		List<Claim> mine = CLAIMS.get(holder);
		if (mine == null) return false;
		for (int i = 0; i < mine.size(); i++) {
			Claim c = mine.get(i);
			if (c.dim().equals(level.dimension()) && c.box().equals(box) && c.expiry() > now) {
				mine.set(i, new Claim(holder, c.scope(), c.dim(), box, now + ttl));
				return true;
			}
		}
		return false;
	}

	public static synchronized boolean holds(ServerLevel level, UUID holder, BoundingBox box) {
		long now = level.getGameTime();
		List<Claim> mine = CLAIMS.get(holder);
		if (mine == null) return false;
		for (Claim c : mine) {
			if (c.dim().equals(level.dimension()) && c.box().equals(box) && c.expiry() > now) return true;
		}
		return false;
	}

	/** Live claims in this dimension whose box intersects `box`, from every holder. */
	public static synchronized List<Claim> overlapping(ServerLevel level, BoundingBox box) {
		long now = level.getGameTime();
		List<Claim> out = new ArrayList<>();
		for (List<Claim> list : CLAIMS.values()) {
			for (Claim c : list) {
				if (c.expiry() > now && c.dim().equals(level.dimension()) && c.box().intersects(box)) out.add(c);
			}
		}
		return out;
	}

	public static synchronized void release(UUID holder, BoundingBox box) {
		List<Claim> mine = CLAIMS.get(holder);
		if (mine == null) return;
		mine.removeIf(c -> c.box().equals(box));
		if (mine.isEmpty()) CLAIMS.remove(holder);
	}

	public static synchronized void releaseAll(UUID holder) {
		CLAIMS.remove(holder);
	}

	private static void prune(long now) {
		Iterator<Map.Entry<UUID, List<Claim>>> it = CLAIMS.entrySet().iterator();
		while (it.hasNext()) {
			List<Claim> list = it.next().getValue();
			list.removeIf(c -> c.expiry() <= now);
			if (list.isEmpty()) it.remove();
		}
	}
}
