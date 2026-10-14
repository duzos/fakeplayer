package dev.duzo.players.compat;

import dev.duzo.players.Constants;
import dev.duzo.players.compat.tacz.TaczFabricEvents;

/** Fabric side of the TACZ support. No TACZ type is named here, the hook that needs one lives in TaczFabricEvents. */
public final class TaczFabricCompat {
	private TaczFabricCompat() {}

	public static void init() {
		if (!TaczGunBridge.present()) return;
		try {
			TaczFabricEvents.register();
		} catch (RuntimeException | LinkageError ex) {
			Constants.LOG.warn("TACZ friendly fire hook failed to register", ex);
		}
	}
}
