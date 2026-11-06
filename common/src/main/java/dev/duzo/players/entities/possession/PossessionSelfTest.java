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
import javax.annotation.Nullable;

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
		ledgerFailures(registries, failures);
		entityReload(server, failures);
		vacancyFlags(server, failures);
		if (player != null) { slotMapping(player, failures); stashVitals(player, failures); initialRecovery(server, player, failures); }
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
			if (!ledger.put(fake, new PossessionLedger.Entry(9L, possessor, false, pending, new CompoundTag(), new CompoundTag(), "minecraft:overworld", false, 1, "round-trip"))) {
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

	private static void ledgerFailures(HolderLookup.Provider registries, List<String> failures) {
		Path directory = null;
		try {
			directory = Files.createTempDirectory("players_possession_checks");
			Path file = directory.resolve("ledger.dat");
			UUID fake = UUID.randomUUID(), owner = UUID.randomUUID();
			PossessionLedger ledger = PossessionLedger.at(file);
			CompoundTag stash = new CompoundTag(); stash.putString("probe", "own inventory");
			CompoundTag shell = new CompoundTag(); shell.putString("probe", "canonical shell");
			PossessionLedger.Entry prepared = new PossessionLedger.Entry(3, owner, true, sampleState().toTag(registries),
					stash, shell, "minecraft:overworld", false, ledger.nextSequence(), "prepare-token");
			if (!ledger.put(fake, prepared)) { failures.add("prepared start did not write"); return; }
			PossessionLedger reload = PossessionLedger.at(file);
			PossessionLedger.Entry back = reload.get(fake);
			if (!reload.available() || back == null || back.stash == null || back.pending == null || back.shell == null
					|| !back.playerToken.equals("prepare-token") || reload.latestFor(owner) == null) {
				failures.add("prepared start lost its recovery payload");
			}
			UUID second = UUID.randomUUID(), other = UUID.randomUUID();
			ledger.put(fake, prepared.checkpointed());
			ledger.put(second, new PossessionLedger.Entry(4, owner, false, prepared.pending, stash, shell,
					"minecraft:overworld", false, ledger.nextSequence(), "second-token").checkpointed());
			ledger.put(second, new PossessionLedger.Entry(5, other, true, prepared.pending, stash, shell,
					"minecraft:overworld", false, ledger.nextSequence(), "other-token"));
			var historical = PossessionLedger.at(file).latestFor(owner);
			if (historical == null || historical.getValue().stash != null || historical.getValue().pending != null) {
				failures.add("completed older possession can replay stale own inventory after another owner replaces the latest fake record");
			}
			ledger.put(fake, prepared);
			Files.delete(file);
			Files.createDirectory(file);
			if (ledger.put(fake, prepared.checkpointed()) || ledger.get(fake) != prepared) {
				failures.add("failed ledger replacement changed the live authority");
			}
			Files.delete(file);
			Files.writeString(file, "not compressed nbt");
			PossessionLedger corrupt = PossessionLedger.at(file);
			if (corrupt.available() || corrupt.put(fake, prepared) || !Files.readString(file).equals("not compressed nbt")) {
				failures.add("unreadable ledger was not quarantined intact");
			}
		} catch (IOException e) {
			failures.add("ledger failure checks: " + e.getMessage());
		} finally {
			if (directory != null) {
				try {
					Files.deleteIfExists(directory.resolve("ledger.dat"));
					Files.deleteIfExists(directory.resolve("ledger.dat.tmp"));
					Files.deleteIfExists(directory);
				} catch (IOException e) { failures.add("ledger check cleanup: " + e.getMessage()); }
			}
		}
	}

	private static void initialRecovery(MinecraftServer server, ServerPlayer player, List<String> failures) {
		Path directory = null;
		CompoundTag before = FakeState.copyFrom(player).toTag(server.registryAccess());
		var position = player.position();
		var oldStash = PossessionStashHolder.stashOf(player);
		String token = ((PossessionStashHolder) player).players$getSaveToken();
		try {
			directory = Files.createTempDirectory("players_initial_recovery");
			Path file = directory.resolve("ledger.dat");
			PossessionLedger ledger = PossessionLedger.at(file);
			UUID fake = UUID.randomUUID();
			FakeState sample = sampleState();
			float initialHealth = player.getMaxHealth() + 5;
			FakeState initial = new FakeState(sample.storage(), sample.equipment(), initialHealth, 8, sample.effects(), 40, 250);
			CompoundTag shell = new CompoundTag();
			var pos = new net.minecraft.nbt.ListTag();
			pos.add(net.minecraft.nbt.DoubleTag.valueOf(12)); pos.add(net.minecraft.nbt.DoubleTag.valueOf(64)); pos.add(net.minecraft.nbt.DoubleTag.valueOf(34));
			shell.put("Pos", pos);
			PlayerStash.Own own = new PlayerStash.Own(java.util.Collections.nCopies(36, ItemStack.EMPTY), 0, Map.of(), 20, 0,
					new CompoundTag(), List.of(), 0, 300, 0, Optional.empty());
			PlayerStash stash = new PlayerStash(PlayerStash.VERSION, own,
					new PlayerStash.Body("minecraft:overworld", 0, 64, 0, 0, 0, UUID.randomUUID()),
					new PlayerStash.Ref(fake, "minecraft:overworld", 1, shell, "test"));
			PossessionLedger.Entry active = new PossessionLedger.Entry(1, player.getUUID(), true, initial.toTag(server.registryAccess()),
					new CompoundTag(), shell, "minecraft:overworld", false, 1, "initial-token");
			ledger.put(fake, active);
			PossessionLedger.Entry prepared = PossessionManager.prepareInitialOutput(server, ledger, player, stash, active);
			var decoded = prepared == null || prepared.pending == null ? Optional.<FakeState>empty() : FakeState.fromTag(server.registryAccess(), prepared.pending);
			if (decoded.isEmpty() || decoded.get().health() != initialHealth || decoded.get().absorption() != 8
					|| !same(decoded.get().storage(), initial.storage())) failures.add("initial recovery clamped fake vitals or changed items through player state");
			ledger.put(fake, active);
			Files.delete(file); Files.createDirectory(file);
			if (PossessionManager.prepareInitialOutput(server, ledger, player, stash, active) != null || ledger.get(fake) != active) {
				failures.add("failed initial recovery changed journal authority");
			}
		} catch (IOException e) { failures.add("initial recovery check: " + e.getMessage()); }
		finally {
			if (!before.equals(FakeState.copyFrom(player).toTag(server.registryAccess())) || !position.equals(player.position())
					|| oldStash != PossessionStashHolder.stashOf(player) || !token.equals(((PossessionStashHolder) player).players$getSaveToken())) {
				failures.add("initial recovery preparation mutated the original player");
			}
			if (directory != null) try {
				Files.deleteIfExists(directory.resolve("ledger.dat")); Files.deleteIfExists(directory.resolve("ledger.dat.tmp")); Files.deleteIfExists(directory);
			} catch (IOException e) { failures.add("initial recovery cleanup: " + e.getMessage()); }
		}
	}

	private static void vacancyFlags(MinecraftServer server, List<String> failures) {
		for (boolean noAi : new boolean[] {false, true}) for (boolean noGravity : new boolean[] {false, true}) {
			var fake = new dev.duzo.players.entities.FakePlayerEntity(server.overworld());
			fake.setNoAi(noAi);
			fake.setNoGravity(noGravity);
			fake.setVacant(UUID.randomUUID(), 1);
			// Start snapshot, unloaded vacant shell, release reconstruction, then ordinary reload.
			for (int step = 0; step < 3; step++) {
				var out = new CompoundTag();
				fake.save(out);
				var loaded = net.minecraft.world.entity.EntityType.loadEntityRecursive(out, server.overworld(),
						net.minecraft.world.entity.EntitySpawnReason.LOAD, entity -> entity);
				if (!(loaded instanceof dev.duzo.players.entities.FakePlayerEntity restored)) {
					failures.add("vacancy flag snapshot could not reload"); return;
				}
				fake = restored;
				if (step == 1) fake.clearVacant(2);
				if (step >= 1 && (fake.isNoAi() != noAi || fake.isNoGravity() != noGravity)) {
					failures.add("vacancy changed configured AI/gravity after release/reload: " + noAi + "/" + noGravity);
				}
			}
		}
	}

	private static void entityReload(MinecraftServer server, List<String> failures) {
		for (boolean terminal : new boolean[] {false, true}) {
			var fake = new dev.duzo.players.entities.FakePlayerEntity(server.overworld());
			fake.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
			fake.getInventory().setItem(34, new ItemStack(Items.BREAD, 3));
			fake.clearVacant(12);
			fake.setPossessionDeathPending(terminal);
			var out = new CompoundTag();
			fake.save(out);
			var loaded = net.minecraft.world.entity.EntityType.loadEntityRecursive(out, server.overworld(),
					net.minecraft.world.entity.EntitySpawnReason.LOAD, entity -> entity);
			if (!(loaded instanceof dev.duzo.players.entities.FakePlayerEntity restored)
					|| restored.getInventory().countItem(Items.DIAMOND) != 5 || restored.getInventory().countItem(Items.BREAD) != 3
					|| restored.possessionEpoch() != 12) failures.add("entity storage lost items during unreconciled reload; terminal=" + terminal);
		}
	}

	private static void stashVitals(ServerPlayer player, List<String> failures) {
		PlayerStash.Body body = new PlayerStash.Body("minecraft:overworld", 0, 0, 0, 0, 0, UUID.randomUUID());
		PlayerStash.Ref fake = new PlayerStash.Ref(UUID.randomUUID(), "minecraft:overworld", 1, new CompoundTag(), "check");
		PlayerStash original = PlayerStash.capture(player, body, fake);
		try {
			player.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, 200, 1));
			player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 1));
			player.setHealth(player.getMaxHealth());
			player.setAbsorptionAmount(8);
			float health = player.getHealth(), absorption = player.getAbsorptionAmount();
			PlayerStash captured = PlayerStash.capture(player, body, fake);
			captured.restoreTo(player);
			if (player.getHealth() != health || player.getAbsorptionAmount() != absorption
					|| player.getEffect(MobEffects.HEALTH_BOOST) == null || player.getEffect(MobEffects.ABSORPTION) == null) {
				failures.add("stash changed boosted health or absorption");
			}
		} finally {
			FakeState.takeFrom(player);
			original.restoreTo(player);
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
