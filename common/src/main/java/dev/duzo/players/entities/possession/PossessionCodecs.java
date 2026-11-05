package dev.duzo.players.entities.possession;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Codecs for the stack, effect and slot types, built on the NBT forms this version stores in its own files. Item stacks here
 * are plain NBT, so the stash and the ledger hold exactly what a vanilla inventory save would.
 */
final class PossessionCodecs {
	private PossessionCodecs() {}

	/** Keeps an empty stack as an empty stack, so slot positions in a list survive. */
	static final Codec<ItemStack> ITEM = CompoundTag.CODEC.xmap(ItemStack::of, stack -> stack.save(new CompoundTag()));

	static final Codec<MobEffectInstance> EFFECT = CompoundTag.CODEC.comapFlatMap(tag -> {
		MobEffectInstance effect = MobEffectInstance.load(tag);
		return effect == null ? DataResult.error(() -> "unknown effect") : DataResult.success(effect);
	}, effect -> effect.save(new CompoundTag()));

	static final Codec<EquipmentSlot> SLOT = Codec.STRING.comapFlatMap(name -> {
		try {
			return DataResult.success(EquipmentSlot.byName(name));
		} catch (IllegalArgumentException e) {
			return DataResult.error(() -> "unknown slot " + name);
		}
	}, EquipmentSlot::getName);
}
