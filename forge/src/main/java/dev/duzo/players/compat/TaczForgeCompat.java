package dev.duzo.players.compat;

import dev.duzo.players.Constants;
import dev.duzo.players.compat.tacz.TaczForgeEvents;

/**
 * Forge side of the TACZ support. No TACZ type is named here, the hook that needs one lives in TaczForgeEvents.
 * The ammo handler is FakePlayerTaczAmmoMixin: LivingEntity answers the item handler capability itself while alive,
 * so a capability attached with AttachCapabilitiesEvent would never be asked.
 */
public final class TaczForgeCompat {
	private TaczForgeCompat() {}

	public static void init() {
		if (!TaczGunBridge.present()) return;
		try {
			TaczForgeEvents.register();
		} catch (RuntimeException | LinkageError ex) {
			Constants.LOG.warn("TACZ friendly fire hook failed to register", ex);
		}
	}
}
