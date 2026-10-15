package dev.duzo.players.entities.possession;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Dev checks behind {@code /players possession selftest}: round trips of the data model, and the slot mapping on the caller's own inventory. */
public final class PossessionSelfTest {
	private static final List<EquipmentSlot> WORN = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND);

	private PossessionSelfTest() {}

	/** The slot mapping is only checked when a player is given, since it takes that player's inventory and puts it straight back. */
	public static List<String> run(MinecraftServer server, @Nullable ServerPlayer player) {
		List<String> failures = new ArrayList<>();
		HolderLookup.Provider registries = server.registryAccess();
		stateRoundTrip(registries, failures);
		stashRoundTrip(registries, failures);
		ledgerRoundTrip(registries, failures);
		if (player != null) slotMapping(player, failures);
		return failures;
	}

	private static FakeState sampleState() {
		List<ItemStack> storage = new ArrayList<>();
		for (int i = 0; i < 35; i++) storage.add(ItemStack.EMPTY);
		ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("round trip"));
		storage.set(0, new ItemStack(Items.DIAMOND, 5));
		storage.set(26, named);
		storage.set(27, new ItemStack(Items.COBBLESTONE, 64));
		storage.set(34, new ItemStack(Items.BREAD, 3));
		Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
		equipment.put(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		equipment.put(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		equipment.put(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		return new FakeState(storage, equipment, 13.5F, 2.0F, List.of(new MobEffectInstance(MobEffects.SPEED, 200, 1)), 40, 250);
	}

	private static boolean same(List<ItemStack> a, List<ItemStack> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
		return true;
	}

	private static void stateRoundTrip(HolderLookup.Provider registries, List<String> failures) {
		FakeState state = sampleState();
		Optional<FakeState> back = FakeState.fromTag(registries, state.toTag(registries));
		if (back.isEmpty()) {
			failures.add("FakeState did not decode");
			return;
		}
		FakeState b = back.get();
		if (!same(state.storage(), b.storage())) failures.add("FakeState storage differs");
		for (EquipmentSlot slot : FakeState.SLOTS) {
			if (!ItemStack.matches(state.equipment().getOrDefault(slot, ItemStack.EMPTY), b.equipment().getOrDefault(slot, ItemStack.EMPTY))) {
				failures.add("FakeState equipment differs at " + slot);
			}
		}
		if (state.health() != b.health() || state.absorption() != b.absorption() || state.fireTicks() != b.fireTicks() || state.air() != b.air()
				|| b.effects().size() != 1) failures.add("FakeState vitals differ");
	}

	private static void stashRoundTrip(HolderLookup.Provider registries, List<String> failures) {
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < 36; i++) items.add(i == 3 ? new ItemStack(Items.APPLE, 2) : ItemStack.EMPTY);
		Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
		worn.put(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		PlayerStash.Own own = new PlayerStash.Own(items, 3, worn, 11.0F, 0.0F, new CompoundTag(), List.of(), 0, 300, 0, Optional.empty());
		PlayerStash stash = new PlayerStash(PlayerStash.VERSION, own, new PlayerStash.Body("minecraft:overworld", 1.5, 64.0, -3.5, 90.0F, 10.0F, UUID.randomUUID()),
				new PlayerStash.Ref(UUID.randomUUID(), "minecraft:overworld", 7L, new CompoundTag(), "Test"));
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
		Optional<PlayerStash> back = PlayerStash.CODEC.encodeStart(ops, stash).result().flatMap(t -> PlayerStash.CODEC.parse(ops, t).result());
		if (back.isEmpty()) {
			failures.add("PlayerStash did not round trip");
			return;
		}
		PlayerStash b = back.get();
		if (!same(stash.own().items(), b.own().items()) || b.own().selected() != 3 || b.fake().epoch() != 7L || !b.body().uuid().equals(stash.body().uuid())
				|| !ItemStack.matches(b.own().equipment().get(EquipmentSlot.CHEST), worn.get(EquipmentSlot.CHEST))) {
			failures.add("PlayerStash fields differ");
		}
	}

	private static void ledgerRoundTrip(HolderLookup.Provider registries, List<String> failures) {
		Path file = null;
		try {
			file = Files.createTempFile("players_possession", ".dat");
			Files.delete(file);
			PossessionLedger ledger = PossessionLedger.at(file);
			UUID fake = UUID.randomUUID();
			UUID possessor = UUID.randomUUID();
			CompoundTag pending = sampleState().toTag(registries);
			if (!ledger.put(fake, new PossessionLedger.Entry(9L, possessor, false, pending))) {
				failures.add("ledger write failed");
				return;
			}
			PossessionLedger.Entry read = PossessionLedger.at(file).get(fake);
			if (read == null || read.epoch != 9L || read.active || !read.possessor.equals(possessor) || read.pending == null
					|| !FakeState.fromTag(registries, read.pending).map(s -> same(s.storage(), sampleState().storage())).orElse(false)) {
				failures.add("ledger entry differs after reload");
			}
		} catch (IOException e) {
			failures.add("ledger temp file: " + e.getMessage());
		} finally {
			if (file != null) {
				try {
					Files.deleteIfExists(file);
				} catch (IOException ignored) {
				}
			}
		}
	}

	/** Takes the caller's inventory into a state and puts it straight back: slots, selected slot and equipment must come out as they went in. */
	private static void slotMapping(ServerPlayer player, List<String> failures) {
		Inventory inv = player.getInventory();
		List<ItemStack> before = new ArrayList<>(inv.getNonEquipmentItems());
		List<ItemStack> wornBefore = new ArrayList<>();
		for (EquipmentSlot slot : WORN) wornBefore.add(player.getItemBySlot(slot));
		int selected = inv.getSelectedSlot();
		ItemStack held = player.getMainHandItem();
		FakeState state = FakeState.takeFrom(player);
		if (!ItemStack.matches(state.equipment().getOrDefault(EquipmentSlot.MAINHAND, ItemStack.EMPTY), held)) failures.add("mapping: held item is not the fake's main hand");
		for (ItemStack stack : inv.getNonEquipmentItems()) {
			if (!stack.isEmpty()) failures.add("mapping: take left an item behind");
		}
		state.applyTo(player);
		List<ItemStack> after = new ArrayList<>(inv.getNonEquipmentItems());
		// the swap puts the held item in slot 0, so compare against the original with the selected slot swapped to 0
		if (selected != 0) {
			ItemStack tmp = before.get(0);
			before.set(0, before.get(selected));
			before.set(selected, tmp);
		}
		if (!same(before, after)) failures.add("mapping: take then apply changed the inventory");
		if (inv.getSelectedSlot() != 0) failures.add("mapping: selected slot not 0");
		for (int i = 0; i < WORN.size(); i++) {
			if (!ItemStack.matches(wornBefore.get(i), player.getItemBySlot(WORN.get(i)))) failures.add("mapping: equipment differs at " + WORN.get(i));
		}
	}
}
