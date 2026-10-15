package dev.duzo.players.entities.possession;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.inventory.FakePlayerInventory;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything that travels between a fake and a possessing player. The {@code take} methods empty their source in the same
 * call, and the {@code applyTo} methods hand the very same stacks over, so an item is only ever in one place.
 *
 * <p>The fake's layout comes from the fake menu: storage 0-26 is the main grid, storage 27-34 is hotbar 2-9 and the main
 * hand is hotbar 1. A player's 36 non-equipment slots map as 0 to the main hand, 1-8 to storage 27-34 and 9-35 to storage 0-26.
 */
public record FakeState(List<ItemStack> storage, Map<EquipmentSlot, ItemStack> equipment, float health, float absorption,
                        List<MobEffectInstance> effects, int fireTicks, int air) {
	/** Armour, offhand and main hand; the main hand is hotbar slot 0 of a player, so a player's stash never lists it twice. */
	public static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

	private static final int HOTBAR_REST = 8;
	private static final int GRID = 27;

	public static final Codec<FakeState> CODEC = RecordCodecBuilder.create(i -> i.group(
			ItemStack.OPTIONAL_CODEC.listOf().fieldOf("storage").forGetter(FakeState::storage),
			Codec.unboundedMap(EquipmentSlot.CODEC, ItemStack.OPTIONAL_CODEC).fieldOf("equipment").forGetter(FakeState::equipment),
			Codec.FLOAT.fieldOf("health").forGetter(FakeState::health),
			Codec.FLOAT.fieldOf("absorption").forGetter(FakeState::absorption),
			MobEffectInstance.CODEC.listOf().fieldOf("effects").forGetter(FakeState::effects),
			Codec.INT.fieldOf("fire").forGetter(FakeState::fireTicks),
			Codec.INT.fieldOf("air").forGetter(FakeState::air)
	).apply(i, (storage, equipment, health, absorption, effects, fire, air) ->
			new FakeState(padded(storage), enumMap(equipment), health, absorption, effects, fire, air)));

	private static Map<EquipmentSlot, ItemStack> enumMap(Map<EquipmentSlot, ItemStack> map) {
		Map<EquipmentSlot, ItemStack> out = new EnumMap<>(EquipmentSlot.class);
		out.putAll(map);
		return out;
	}

	private static List<ItemStack> padded(List<ItemStack> stacks) {
		List<ItemStack> out = new ArrayList<>(stacks);
		while (out.size() < FakePlayerInventory.SIZE) out.add(ItemStack.EMPTY);
		return out;
	}

	/** Moves the fake's inventory, equipment and vitals out of it. */
	public static FakeState takeFrom(FakePlayerEntity fake) {
		FakePlayerInventory inv = fake.getInventory();
		List<ItemStack> storage = new ArrayList<>(FakePlayerInventory.SIZE);
		for (int i = 0; i < FakePlayerInventory.SIZE; i++) {
			storage.add(inv.getItem(i));
			inv.setItem(i, ItemStack.EMPTY);
		}
		Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
		for (EquipmentSlot slot : SLOTS) {
			equipment.put(slot, fake.getItemBySlot(slot));
			fake.setItemSlot(slot, ItemStack.EMPTY);
		}
		return new FakeState(storage, equipment, fake.getHealth(), fake.getAbsorptionAmount(), vitalEffects(fake),
				fake.getRemainingFireTicks(), fake.getAirSupply());
	}

	/** Moves a player's whole inventory out of them. The held item becomes the main hand, so the fake holds what the player held. */
	public static FakeState takeFrom(ServerPlayer player) {
		Inventory inv = player.getInventory();
		NonNullList<ItemStack> items = inv.getNonEquipmentItems();
		int selected = inv.getSelectedSlot();
		if (selected != 0) {
			ItemStack held = items.get(selected);
			items.set(selected, items.get(0));
			items.set(0, held);
		}
		List<ItemStack> storage = new ArrayList<>(FakePlayerInventory.SIZE);
		for (int i = 0; i < FakePlayerInventory.SIZE; i++) storage.add(ItemStack.EMPTY);
		Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
		equipment.put(EquipmentSlot.MAINHAND, items.get(0));
		items.set(0, ItemStack.EMPTY);
		for (int i = 1; i <= HOTBAR_REST; i++) {
			storage.set(GRID + i - 1, items.get(i));
			items.set(i, ItemStack.EMPTY);
		}
		for (int i = 0; i < GRID; i++) {
			storage.set(i, items.get(9 + i));
			items.set(9 + i, ItemStack.EMPTY);
		}
		inv.setSelectedSlot(0);
		for (EquipmentSlot slot : SLOTS) {
			if (slot == EquipmentSlot.MAINHAND) continue;
			equipment.put(slot, player.getItemBySlot(slot));
			player.setItemSlot(slot, ItemStack.EMPTY);
		}
		return new FakeState(storage, equipment, player.getHealth(), player.getAbsorptionAmount(), vitalEffects(player),
				player.getRemainingFireTicks(), player.getAirSupply());
	}

	/** Puts this state into an empty fake. The fake's own inventory must already be empty, which the caller checks. */
	public void applyTo(FakePlayerEntity fake) {
		FakePlayerInventory inv = fake.getInventory();
		for (int i = 0; i < FakePlayerInventory.SIZE; i++) inv.setItem(i, storage.get(i));
		for (EquipmentSlot slot : SLOTS) fake.setItemSlot(slot, equipment.getOrDefault(slot, ItemStack.EMPTY));
		applyVitals(fake);
	}

	/** Puts this state into a player whose inventory must already be empty. */
	public void applyTo(ServerPlayer player) {
		Inventory inv = player.getInventory();
		NonNullList<ItemStack> items = inv.getNonEquipmentItems();
		inv.setSelectedSlot(0);
		items.set(0, equipment.getOrDefault(EquipmentSlot.MAINHAND, ItemStack.EMPTY));
		for (int i = 1; i <= HOTBAR_REST; i++) items.set(i, storage.get(GRID + i - 1));
		for (int i = 0; i < GRID; i++) items.set(9 + i, storage.get(i));
		for (EquipmentSlot slot : SLOTS) {
			if (slot == EquipmentSlot.MAINHAND) continue;
			player.setItemSlot(slot, equipment.getOrDefault(slot, ItemStack.EMPTY));
		}
		applyVitals(player);
	}

	private void applyVitals(LivingEntity entity) {
		entity.removeAllEffects();
		for (MobEffectInstance effect : effects) entity.addEffect(new MobEffectInstance(effect));
		entity.setHealth(health);
		entity.setAbsorptionAmount(absorption);
		entity.setRemainingFireTicks(fireTicks);
		entity.setAirSupply(air);
	}

	static List<MobEffectInstance> vitalEffects(LivingEntity entity) {
		List<MobEffectInstance> out = new ArrayList<>();
		for (MobEffectInstance effect : entity.getActiveEffects()) out.add(new MobEffectInstance(effect));
		entity.removeAllEffects();
		return out;
	}

	public boolean hasItems() {
		for (ItemStack stack : storage) if (!stack.isEmpty()) return true;
		for (ItemStack stack : equipment.values()) if (!stack.isEmpty()) return true;
		return false;
	}

	/** Every stack in this state, for counting. */
	public List<ItemStack> allStacks() {
		List<ItemStack> out = new ArrayList<>(storage);
		out.addAll(equipment.values());
		return out;
	}

	/** An independent serialised snapshot, for the ledger. The live stacks stay where they are. */
	public CompoundTag toTag(HolderLookup.Provider registries) {
		RegistryOps<net.minecraft.nbt.Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
		return (CompoundTag) CODEC.encodeStart(ops, this).getOrThrow(IllegalStateException::new);
	}

	public static Optional<FakeState> fromTag(HolderLookup.Provider registries, CompoundTag tag) {
		RegistryOps<net.minecraft.nbt.Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
		return CODEC.parse(ops, tag).result();
	}
}
