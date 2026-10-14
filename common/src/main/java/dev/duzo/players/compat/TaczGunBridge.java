package dev.duzo.players.compat;

import dev.duzo.players.Constants;
import dev.duzo.players.compat.tacz.TaczGuns;
import dev.duzo.players.entities.ai.FriendlyFire;
import dev.duzo.players.platform.Services;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Safe to call without TACZ installed. Nothing here names a TACZ type, and TaczGuns is only reached behind present(). */
public final class TaczGunBridge {
	private TaczGunBridge() {}

	/** What one attempt to shoot came to, for the goal to react to. */
	public enum Shot { FIRED, BOLTING, RELOADING, NOT_DRAWN, SPRINTING, WAIT, UNUSABLE, FAILED }

	/** 1.1.8 is the TACZ release the API was checked against; Refabricated carries it as "forge1.1.8". */
	private static final int[] MIN_VERSION = {1, 1, 8};
	private static final Pattern UPSTREAM = Pattern.compile("forge(\\d+(?:\\.\\d+)*)");
	private static volatile boolean disabled;
	private static Boolean supported;

	public static boolean present() {
		if (disabled || !Services.PLATFORM.isModLoaded("tacz")) return false;
		if (supported == null) {
			String version = Services.PLATFORM.getModVersion("tacz");
			String upstream = version;
			if (version != null) {
				Matcher m = UPSTREAM.matcher(version);
				if (m.find()) upstream = m.group(1);
			}
			supported = upstream != null && TideFishingBridge.atLeast(upstream, MIN_VERSION);
			if (!supported) Constants.LOG.warn("TACZ {} is older than 1.1.8, gun support is off", version);
		}
		return supported;
	}

	public static boolean isGun(ItemStack stack) {
		if (!present()) return false;
		try {
			return TaczGuns.isGun(stack);
		} catch (RuntimeException | LinkageError ex) {
			disable("gun check", ex);
			return false;
		}
	}

	public static boolean canFire(LivingEntity shooter, ItemStack stack) {
		if (!present()) return false;
		try {
			boolean can = TaczGuns.canFire(shooter, stack);
			if (Constants.TRACE) Constants.trace("tacz-canfire-" + shooter.getId(), "tacz canFire {} -> {}", stack.getItem(), can);
			return can;
		} catch (RuntimeException | LinkageError ex) {
			disable("ammo check", ex);
			return false;
		}
	}

	/** Draws the gun held in the given hand; call when it enters the hand, before any shot. */
	public static boolean ready(LivingEntity shooter, InteractionHand hand) {
		if (!present()) return false;
		try {
			TaczGuns.ready(shooter, hand);
			Constants.traceEvent("tacz ready {} in {}", shooter.getItemInHand(hand).getItem(), hand);
			return true;
		} catch (RuntimeException | LinkageError ex) {
			disable("draw", ex);
			return false;
		}
	}

	public static Shot fire(LivingEntity shooter, float pitch, float yaw) {
		if (!present()) return Shot.FAILED;
		try {
			Shot shot = TaczGuns.fire(shooter, pitch, yaw);
			if (shot != Shot.WAIT) Constants.traceEvent("tacz shot {} pitch={} yaw={}", shot, pitch, yaw);
			return shot;
		} catch (RuntimeException | LinkageError ex) {
			disable("shot", ex);
			return Shot.FAILED;
		}
	}

	public static void stop(LivingEntity shooter) {
		if (!present()) return;
		try {
			TaczGuns.stop(shooter);
			Constants.traceEvent("tacz stop");
		} catch (RuntimeException | LinkageError ex) {
			disable("stop", ex);
		}
	}

	/** Friendly fire guard for the loaders' hurt-by-gun hook, the same rule as FriendlyFire applies to any other damage. */
	public static boolean shouldBlockHit(Entity attacker, Entity victim) {
		return FriendlyFire.shouldBlock(attacker, victim);
	}

	// deliberately server-wide: a throw means the TACZ API shape is not what was checked, and that holds for every fake
	private static void disable(String what, Throwable ex) {
		disabled = true;
		Constants.LOG.warn("TACZ {} threw, disabling gun support", what, ex);
	}
}
