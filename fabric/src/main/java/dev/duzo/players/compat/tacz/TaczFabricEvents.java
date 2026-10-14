package dev.duzo.players.compat.tacz;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import dev.duzo.players.compat.TaczGunBridge;

/** Holds the one TACZ event type Fabric needs. Only load it behind TaczGunBridge.present(). */
public final class TaczFabricEvents {
	private TaczFabricEvents() {}

	public static void register() {
		EntityHurtByGunEvent.PRE.register(TaczFabricEvents::onHurtByGun);
	}

	private static void onHurtByGun(EntityHurtByGunEvent.Pre event) {
		if (TaczGunBridge.shouldBlockHit(event.getAttacker(), event.getHurtEntity())) event.setCanceled(true);
	}
}
