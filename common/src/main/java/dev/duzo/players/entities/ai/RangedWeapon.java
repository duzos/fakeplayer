package dev.duzo.players.entities.ai;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * The ranged weapons a fake knows how to fight with. Matched on item class rather than on the vanilla
 * instances so that modded bows and crossbows are picked up too.
 */
public enum RangedWeapon {
	BOW,
	CROSSBOW,
	TRIDENT;

	@Nullable
	public static RangedWeapon of(ItemStack stack) {
		if (stack.isEmpty()) return null;
		if (stack.getItem() instanceof BowItem) return BOW;
		// deliberately item identity, not instanceof: firing goes through vanilla performCrossbowAttack, which
		// resolves the hand with ProjectileUtil.getWeaponHoldingHand(Items.CROSSBOW) and silently falls back to
		// the offhand. A modded crossbow would charge, eat an arrow and never fire.
		if (stack.is(Items.CROSSBOW)) return CROSSBOW;
		if (stack.getItem() instanceof TridentItem) return TRIDENT;
		return null;
	}

	/** The hand holding a ranged weapon, main hand first, or null if neither does. */
	@Nullable
	public static InteractionHand handHolding(LivingEntity entity) {
		for (InteractionHand hand : InteractionHand.values()) {
			if (of(entity.getItemInHand(hand)) != null) return hand;
		}
		return null;
	}

	/** What counts as ammunition for this weapon. A trident is its own ammunition. */
	public static Predicate<ItemStack> ammo(ItemStack weapon) {
		if (weapon.getItem() instanceof ProjectileWeaponItem projectile) {
			return projectile.getAllSupportedProjectiles();
		}
		return stack -> false;
	}
}
