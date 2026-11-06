package dev.duzo.players.entities.possession;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A possessing player's own state, set aside while they are in a fake's body. It lives in the player's NBT, so it goes
 * wherever the player file goes, and it is what login repair puts back after a crash.
 */
public record PlayerStash(int version, Own own, Body body, Ref fake) {
	public static final int VERSION = 1;
	public static final String KEY = "players:possession";
	private static final int PLAYER_SLOTS = 36;
	private static final List<EquipmentSlot> WORN = List.of(EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
			EquipmentSlot.LEGS, EquipmentSlot.FEET);

	/** The player's inventory and vitals. The main hand is part of {@code items}, not of {@code equipment}. */
	public record Own(List<ItemStack> items, int selected, Map<EquipmentSlot, ItemStack> equipment, float health, float absorption,
	                  CompoundTag food, List<MobEffectInstance> effects, int fireTicks, int air, int gameMode,
	                  Optional<ServerPlayer.RespawnConfig> respawn) {
		static final Codec<Own> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(Own::items),
				Codec.INT.fieldOf("selected").forGetter(Own::selected),
				Codec.unboundedMap(EquipmentSlot.CODEC, ItemStack.OPTIONAL_CODEC).fieldOf("equipment").forGetter(Own::equipment),
				Codec.FLOAT.fieldOf("health").forGetter(Own::health),
				Codec.FLOAT.fieldOf("absorption").forGetter(Own::absorption),
				CompoundTag.CODEC.fieldOf("food").forGetter(Own::food),
				MobEffectInstance.CODEC.listOf().fieldOf("effects").forGetter(Own::effects),
				Codec.INT.fieldOf("fire").forGetter(Own::fireTicks),
				Codec.INT.fieldOf("air").forGetter(Own::air),
				Codec.INT.fieldOf("game_mode").forGetter(Own::gameMode),
				ServerPlayer.RespawnConfig.CODEC.optionalFieldOf("respawn").forGetter(Own::respawn)
		).apply(i, (items, selected, equipment, health, absorption, food, effects, fire, air, mode, respawn) ->
				new Own(padded(items), selected, enumMap(equipment), health, absorption, food, effects, fire, air, mode, respawn)));
	}

	/** Where the body stands, and which entity it is. */
	public record Body(String dimension, double x, double y, double z, float yaw, float pitch, UUID uuid) {
		static final Codec<Body> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("dimension").forGetter(Body::dimension),
				Codec.DOUBLE.fieldOf("x").forGetter(Body::x),
				Codec.DOUBLE.fieldOf("y").forGetter(Body::y),
				Codec.DOUBLE.fieldOf("z").forGetter(Body::z),
				Codec.FLOAT.fieldOf("yaw").forGetter(Body::yaw),
				Codec.FLOAT.fieldOf("pitch").forGetter(Body::pitch),
				UUIDUtil.CODEC.fieldOf("uuid").forGetter(Body::uuid)
		).apply(i, Body::new));
	}

	/** The possessed fake: its epoch while vacant, and a snapshot of its empty shell to rebuild it from if it is gone. */
	public record Ref(UUID uuid, String dimension, long epoch, CompoundTag shellSnapshot, String name) {
		static final Codec<Ref> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.CODEC.fieldOf("uuid").forGetter(Ref::uuid),
				Codec.STRING.fieldOf("dimension").forGetter(Ref::dimension),
				Codec.LONG.fieldOf("epoch").forGetter(Ref::epoch),
				CompoundTag.CODEC.fieldOf("shell").forGetter(Ref::shellSnapshot),
				Codec.STRING.fieldOf("name").forGetter(Ref::name)
		).apply(i, Ref::new));
	}

	public static final Codec<PlayerStash> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("version").forGetter(PlayerStash::version),
			Own.CODEC.fieldOf("own").forGetter(PlayerStash::own),
			Body.CODEC.fieldOf("body").forGetter(PlayerStash::body),
			Ref.CODEC.fieldOf("fake").forGetter(PlayerStash::fake)
	).apply(i, PlayerStash::new));

	private static List<ItemStack> padded(List<ItemStack> stacks) {
		List<ItemStack> out = new ArrayList<>(stacks);
		while (out.size() < PLAYER_SLOTS) out.add(ItemStack.EMPTY);
		return out;
	}

	private static Map<EquipmentSlot, ItemStack> enumMap(Map<EquipmentSlot, ItemStack> map) {
		Map<EquipmentSlot, ItemStack> out = new EnumMap<>(EquipmentSlot.class);
		out.putAll(map);
		return out;
	}

	/**
	 * Moves everything the player owns into a stash and leaves their inventory and effects empty. Food and game mode are read,
	 * not changed. Everything that could throw is read first, and the stacks move last, so a failure never strands items.
	 */
	public static PlayerStash capture(ServerPlayer player, Body body, Ref fake) {
		Inventory inv = player.getInventory();
		CompoundTag food = new CompoundTag();
		player.getFoodData().addAdditionalSaveData(food);
		Optional<ServerPlayer.RespawnConfig> respawn = Optional.ofNullable(player.getRespawnConfig());
		int gameMode = player.gameMode.getGameModeForPlayer().getId();
		float health = player.getHealth();
		float absorption = player.getAbsorptionAmount();
		int fire = player.getRemainingFireTicks();
		int air = player.getAirSupply();
		List<MobEffectInstance> effects = FakeState.vitalEffects(player);
		int selected = inv.getSelectedSlot();

		NonNullList<ItemStack> slots = inv.getNonEquipmentItems();
		List<ItemStack> items = new ArrayList<>(PLAYER_SLOTS);
		for (int i = 0; i < PLAYER_SLOTS; i++) {
			items.add(slots.get(i));
			slots.set(i, ItemStack.EMPTY);
		}
		Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
		for (EquipmentSlot slot : WORN) {
			equipment.put(slot, player.getItemBySlot(slot));
			player.setItemSlot(slot, ItemStack.EMPTY);
		}
		return new PlayerStash(VERSION, new Own(items, selected, equipment, health, absorption, food, effects, fire, air, gameMode, respawn), body, fake);
	}

	/**
	 * Hands everything back. The player's inventory must already be empty, which the caller guarantees by taking the fake's
	 * state out first. Game mode, max health and position are the caller's, since they depend on how the stash is being unwound.
	 */
	public void restoreTo(ServerPlayer player) {
		Inventory inv = player.getInventory();
		NonNullList<ItemStack> slots = inv.getNonEquipmentItems();
		for (int i = 0; i < PLAYER_SLOTS; i++) slots.set(i, own.items.get(i));
		inv.setSelectedSlot(own.selected);
		for (EquipmentSlot slot : WORN) player.setItemSlot(slot, own.equipment.getOrDefault(slot, ItemStack.EMPTY));
		player.removeAllEffects();
		for (MobEffectInstance effect : own.effects) player.addEffect(new MobEffectInstance(effect));
		player.setHealth(own.health);
		player.setAbsorptionAmount(own.absorption);
		player.setRemainingFireTicks(own.fireTicks);
		player.setAirSupply(own.air);
		player.getFoodData().readAdditionalSaveData(own.food);
		player.setRespawnPosition(own.respawn.orElse(null), false);
	}

	public GameType gameMode() {
		return GameType.byId(own.gameMode);
	}

	/** Every stack, for counting. */
	public List<ItemStack> allStacks() {
		List<ItemStack> out = new ArrayList<>(own.items);
		out.addAll(own.equipment.values());
		return out;
	}
}
