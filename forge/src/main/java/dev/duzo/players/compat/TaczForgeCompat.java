package dev.duzo.players.compat;

import dev.duzo.players.Constants;
import dev.duzo.players.compat.tacz.TaczForgeEvents;
import dev.duzo.players.core.FPEntities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/** NeoForge side of the TACZ support. No TACZ type is named here, the hook that needs one lives in TaczForgeEvents. */
public final class TaczForgeCompat {
	private TaczForgeCompat() {}

	public static void init(IEventBus modBus) {
		modBus.addListener(TaczForgeCompat::registerCapabilities);
		if (!TaczGunBridge.present()) return;
		try {
			TaczForgeEvents.register();
		} catch (RuntimeException | LinkageError ex) {
			Constants.LOG.warn("TACZ friendly fire hook failed to register", ex);
		}
	}

	/**
	 * TACZ looks for ammo with getCapability(ItemHandler.ENTITY, null), and a mob has none by default, so without
	 * this a fake's gun can only fire what is already in its magazine. Skipped without TACZ because the capability
	 * is visible to every mod that asks for it.
	 */
	private static void registerCapabilities(RegisterCapabilitiesEvent event) {
		if (!TaczGunBridge.present()) return;
		event.registerEntity(Capabilities.ItemHandler.ENTITY, FPEntities.FAKE_PLAYER.get(),
				(fake, context) -> new InvWrapper(fake.getInventory()));
		Constants.traceEvent("tacz ammo: fake inventory exposed as an item handler");
	}
}
