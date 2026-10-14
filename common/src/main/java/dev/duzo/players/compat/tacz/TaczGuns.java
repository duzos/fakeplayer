package dev.duzo.players.compat.tacz;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import dev.duzo.players.compat.TaczGunBridge.Shot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Every TACZ type the mod touches in shared code lives in this class. Only load it behind TaczGunBridge.present(),
 * and keep TACZ types out of its public signatures so the bridge never links against them.
 */
public final class TaczGuns {
	private TaczGuns() {}

	public static boolean isGun(ItemStack stack) {
		return IGun.getIGunOrNull(stack) != null;
	}

	/** True while the gun can still shoot or is on its way to being able to: rounds loaded, a reload running, or ammo to reload from. */
	public static boolean canFire(LivingEntity shooter, ItemStack stack) {
		IGun gun = IGun.getIGunOrNull(stack);
		if (gun == null) return false;
		IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
		var reload = operator.getDataHolder().reloadStateType;
		if (reload != null && reload.isReloading()) return true;
		if (gun.getCurrentAmmoCount(stack) > 0 || gun.hasBulletInBarrel(stack)) return true;
		if (gun.useInventoryAmmo(stack)) return gun.hasInventoryAmmo(shooter, stack, operator.needCheckAmmo());
		return gun instanceof AbstractGunItem logic && logic.canReload(shooter, stack);
	}

	/** Shooting before this runs crashes: the bullet constructor requires the cache property it fills in. */
	public static void ready(LivingEntity shooter, InteractionHand hand) {
		IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
		operator.initialData();
		operator.draw(() -> shooter.getItemInHand(hand));
		operator.aim(true);
	}

	public static Shot fire(LivingEntity shooter, float pitch, float yaw) {
		IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
		operator.aim(true);
		ShootResult result = operator.shoot(() -> pitch, () -> yaw);
		switch (result) {
			case SUCCESS:
				return Shot.FIRED;
			case NEED_BOLT:
				operator.bolt();
				return Shot.BOLTING;
			case NO_AMMO:
				operator.reload();
				return Shot.RELOADING;
			case NOT_DRAW:
				return Shot.NOT_DRAWN;
			case IS_SPRINTING:
				return Shot.SPRINTING;
			case COOL_DOWN, IS_RELOADING, IS_DRAWING, IS_BOLTING, IS_MELEE, OVERHEATED:
				return Shot.WAIT;
			case ID_NOT_EXIST, NOT_GUN:
				return Shot.UNUSABLE;
			default:
				return Shot.FAILED;
		}
	}

	public static void stop(LivingEntity shooter) {
		IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
		operator.aim(false);
		var reload = operator.getDataHolder().reloadStateType;
		if (reload != null && reload.isReloading()) operator.cancelReload();
	}
}
