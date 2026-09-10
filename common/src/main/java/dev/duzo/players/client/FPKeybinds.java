package dev.duzo.players.client;

import commonnetwork.api.Network;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.network.c2s.CustomBindStatePacketC2S;
import dev.duzo.players.network.c2s.OpenFakeMenuPacketC2S;
import dev.duzo.players.api.CustomBindTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

/**
 * The open-menu keybind. Unbound by default, in which case sneak + right-click keeps working as it always
 * has. Binding a key moves the interaction off right-click entirely, so mods that claim right-click on mobs
 * no longer swallow it.
 */
public class FPKeybinds {
	public static final String OPEN_MENU_KEY = "key.players.open_menu";

	// 1.20.4's KeyMapping ctor takes the category as a plain translation-key String; there is no
	// KeyMapping.Category yet, so no Category needs registering here.
	public static final KeyMapping OPEN_MENU = new KeyMapping(OPEN_MENU_KEY, GLFW.GLFW_KEY_UNKNOWN, "key.category.players.main");

	private static Boolean lastSentBound;
	private static LocalPlayer lastSentFor;

	private FPKeybinds() {}

	public static boolean isBound() {
		return !OPEN_MENU.isUnbound();
	}

	public static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			lastSentFor = null;
			lastSentBound = null;
			return;
		}

		syncBindState(player);

		// consume every queued press so a press during a screen does not fire later
		boolean pressed = false;
		while (OPEN_MENU.consumeClick()) {
			pressed = true;
		}
		if (!pressed || client.screen != null) return;

		FakePlayerEntity looked = lookedAtFake(client);
		if (looked != null) {
			Network.getNetworkHandler().sendToServer(new OpenFakeMenuPacketC2S(looked.getId()));
		}
	}

	// Re-sent on a fresh player instance as well as on a change, so joining and respawning both re-register.
	private static void syncBindState(LocalPlayer player) {
		boolean bound = isBound();
		CustomBindTracker.setLocal(bound);
		if (player == lastSentFor && lastSentBound != null && lastSentBound == bound) return;

		Network.getNetworkHandler().sendToServer(new CustomBindStatePacketC2S(bound));
		lastSentFor = player;
		lastSentBound = bound;
	}

	private static FakePlayerEntity lookedAtFake(Minecraft client) {
		HitResult hit = client.crosshairPickEntity != null
				? new EntityHitResult(client.crosshairPickEntity)
				: client.hitResult;
		if (!(hit instanceof EntityHitResult entityHit)) return null;

		Entity entity = entityHit.getEntity();
		return entity instanceof FakePlayerEntity fake ? fake : null;
	}
}
