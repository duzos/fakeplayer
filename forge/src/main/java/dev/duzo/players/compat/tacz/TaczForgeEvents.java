package dev.duzo.players.compat.tacz;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import dev.duzo.players.compat.TaczGunBridge;
import net.minecraftforge.common.MinecraftForge;

/** Holds the one TACZ event type Forge needs. Only load it behind TaczGunBridge.present(). */
public final class TaczForgeEvents {
	private TaczForgeEvents() {}

	public static void register() {
		MinecraftForge.EVENT_BUS.addListener(TaczForgeEvents::onHurtByGun);
	}

	private static void onHurtByGun(EntityHurtByGunEvent.Pre event) {
		if (TaczGunBridge.shouldBlockHit(event.getAttacker(), event.getHurtEntity())) event.setCanceled(true);
	}
}
