package dev.duzo.players.compat;

import dev.duzo.players.entities.ai.Tackle;
import dev.duzo.players.entities.ai.TackleLook;
import dev.duzo.players.platform.Services;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.storage.loot.LootTable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aquaculture support, deliberately per-mod and deliberately not in {@code common}: Aquaculture has no
 * Fabric build. Nothing here compiles against it. The rod's tackle lives in a vanilla
 * {@link ItemContainerContents} data component keyed {@code aquaculture:rod_inventory} (slot 0 hook,
 * 1 bait, 2 line, 3 bobber), and every hook's numbers are read off its item id, so the whole module is
 * vanilla API plus string ids and degrades to {@link Tackle#PLAIN} when the mod is absent.
 *
 * <p>Their own {@code AquaFishingRodItem.use()} rejects a FakePlayer outright, so none of this goes
 * through their use path; the Fake Players bobber is driven directly instead.
 */
public final class AquacultureCompat {
	private AquacultureCompat() {}

	private static final String MOD_ID = "aquaculture";
	/** The separate addon that turns on the lava fluid for lava-capable hooks. */
	private static final String LAVA_ADDON_ID = "aq2lava";

	private static final Identifier ROD_INVENTORY_ID = Identifier.fromNamespaceAndPath(MOD_ID, "rod_inventory");
	private static final Identifier NOTE_CATCH_SOUND_ID = Identifier.fromNamespaceAndPath(MOD_ID, "bobber_note_catch");
	private static final Identifier NEPTUNIUM_ROD_ID = Identifier.fromNamespaceAndPath(MOD_ID, "neptunium_fishing_rod");

	private static final ResourceKey<LootTable> LAVA_TABLE = lootTable("gameplay/fishing/lava/fishing");
	private static final ResourceKey<LootTable> NETHER_TABLE = lootTable("gameplay/fishing/nether/fishing");

	private static final int HOOK_SLOT = 0;
	private static final int BAIT_SLOT = 1;
	private static final int LINE_SLOT = 2;
	private static final int BOBBER_SLOT = 3;

	private static final Identifier DEFAULT_HOOK_TEXTURE = texture("hook/hook");
	private static final Identifier BOBBER_TEXTURE = texture("bobber/bobber");
	private static final Identifier BOBBER_OVERLAY_TEXTURE = texture("bobber/bobber_overlay");
	private static final Identifier BOBBER_VANILLA_TEXTURE = texture("bobber/bobber_vanilla");
	/** The red their renderer tints an unfitted bobber with. */
	private static final int DEFAULT_BOBBER_COLOR = ARGB.color(193, 38, 38);

	/** Aquaculture's own hook numbers, plus the lava hooks the aq2lava addon registers under their namespace. */
	private record HookStats(int luck, double doubleCatch, double durabilitySkip, boolean water, boolean lava,
	                         boolean noteSound) {}

	private static final Map<String, HookStats> HOOKS = Map.ofEntries(
			Map.entry("iron_hook", new HookStats(0, 0.00D, 0.20D, true, false, false)),
			Map.entry("gold_hook", new HookStats(1, 0.00D, 0.00D, true, false, false)),
			Map.entry("diamond_hook", new HookStats(0, 0.00D, 0.50D, true, false, false)),
			Map.entry("light_hook", new HookStats(0, 0.00D, 0.00D, true, false, false)),
			Map.entry("heavy_hook", new HookStats(0, 0.00D, 0.00D, true, false, false)),
			Map.entry("double_hook", new HookStats(0, 0.10D, 0.00D, true, false, false)),
			Map.entry("redstone_hook", new HookStats(0, 0.00D, 0.00D, true, false, false)),
			Map.entry("note_hook", new HookStats(0, 0.00D, 0.00D, true, false, true)),
			Map.entry("nether_star_hook", new HookStats(1, 0.00D, 0.50D, true, true, false)),
			Map.entry("obsidian_hook", new HookStats(0, 0.00D, 0.00D, false, true, false)),
			Map.entry("double_obsidian_hook", new HookStats(0, 0.15D, 0.00D, false, true, false)),
			Map.entry("glowstone_hook", new HookStats(1, 0.00D, 0.00D, false, true, false)),
			Map.entry("quartz_hook", new HookStats(0, 0.00D, 0.30D, false, true, false)),
			Map.entry("soul_sand_hook", new HookStats(0, 0.00D, 0.00D, false, true, false)),
			Map.entry("obsidian_note_hook", new HookStats(0, 0.00D, 0.00D, false, true, true))
	);

	private static Boolean present;
	private static Boolean lavaAddon;

	public static boolean isPresent() {
		if (present == null) present = Services.PLATFORM.isModLoaded(MOD_ID);
		return present;
	}

	private static boolean lavaAddonPresent() {
		if (lavaAddon == null) lavaAddon = Services.PLATFORM.isModLoaded(LAVA_ADDON_ID);
		return lavaAddon;
	}

	public static Tackle read(ItemStack rod) {
		if (rod.isEmpty()) return Tackle.PLAIN;

		List<ItemStack> slots = rodInventory(rod);
		Identifier fitted = hookId(slots);
		HookStats hook = fitted == null ? null : HOOKS.get(fitted.getPath());

		// Bait is only ever an IBaitItem, which the slot enforces, and every bait Aquaculture ships gives the
		// same one step of lure speed. Reading the interface would need a compile dependency on a mod that has
		// no Fabric build and no release for some of these versions, so the shipped value is assumed instead.
		int lure = slots.size() > BAIT_SLOT && !slots.get(BAIT_SLOT).isEmpty() ? 1 : 0;
		Identifier rodId = BuiltInRegistries.ITEM.getKey(rod.getItem());
		if (NEPTUNIUM_ROD_ID.equals(rodId)) lure += 1;

		boolean ours = rodId != null && MOD_ID.equals(rodId.getNamespace());
		TackleLook look = ours ? look(slots, fitted) : TackleLook.PLAIN;

		if (hook == null) return new Tackle(true, false, 0, lure, 0.0D, 0.0D, null, null, null, look);

		boolean lava = hook.lava() && lavaAddonPresent();
		return new Tackle(
				hook.water(),
				lava,
				hook.luck(),
				lure,
				hook.doubleCatch(),
				hook.durabilitySkip(),
				lava ? LAVA_TABLE : null,
				lava ? NETHER_TABLE : null,
				hook.noteSound() ? sound(NOTE_CATCH_SOUND_ID) : null,
				look);
	}

	/** Damage or shrink the fitted bait, exactly as their own hook entity does on a landed catch. */
	public static void consumeBait(ServerLevel level, ItemStack rod) {
		DataComponentType<ItemContainerContents> type = rodInventoryType();
		if (type == null) return;
		ItemContainerContents contents = rod.get(type);
		if (contents == null) return;

		List<ItemStack> slots = new ArrayList<>(contents.stream().map(ItemStack::copy).toList());
		if (slots.size() <= BAIT_SLOT) return;
		ItemStack bait = slots.get(BAIT_SLOT);
		if (bait.isEmpty()) return;

		if (bait.isDamageableItem()) {
			// Deliberately not ItemStack.hurtAndBreak here. That shrinks the stack itself before calling the
			// break callback, so the mod's own "shrink in the callback" spends two baits per break, and it
			// leaves the damage at maximum, which breaks another the very next catch. A stack of sixteen
			// worms is gone in under thirty casts instead of lasting three hundred. Count down by hand and
			// reset the damage so each bait in the stack gets its own full life.
			bait.setDamageValue(bait.getDamageValue() + 1);
			if (bait.getDamageValue() >= bait.getMaxDamage()) {
				bait.shrink(1);
				bait.setDamageValue(0);
			}
		} else {
			bait.shrink(1);
		}
		slots.set(BAIT_SLOT, bait);
		rod.set(type, ItemContainerContents.fromItems(slots));
	}

	/** The Aquaculture hook fitted in slot 0, or null. */
	@Nullable
	private static Identifier hookId(List<ItemStack> slots) {
		if (slots.size() <= HOOK_SLOT || slots.get(HOOK_SLOT).isEmpty()) return null;
		Identifier id = BuiltInRegistries.ITEM.getKey(slots.get(HOOK_SLOT).getItem());
		return id != null && MOD_ID.equals(id.getNamespace()) ? id : null;
	}

	/** Mirrors their renderer: a bobber layer tinted by its dye, then the hook's own texture over it. */
	private static TackleLook look(List<ItemStack> slots, @Nullable Identifier fittedHook) {
		Identifier hookTexture = fittedHook == null
				? DEFAULT_HOOK_TEXTURE
				: texture("hook/" + fittedHook.getPath());

		ItemStack bobber = slot(slots, BOBBER_SLOT);
		boolean hasBobber = !bobber.isEmpty();
		int bobberColor = hasBobber ? dye(bobber, DEFAULT_BOBBER_COLOR) : DEFAULT_BOBBER_COLOR;

		return new TackleLook(
				hookTexture,
				hasBobber ? BOBBER_TEXTURE : null,
				hasBobber ? BOBBER_OVERLAY_TEXTURE : BOBBER_VANILLA_TEXTURE,
				bobberColor,
				dye(slot(slots, LINE_SLOT), ARGB.color(0, 0, 0)));
	}

	private static ItemStack slot(List<ItemStack> slots, int index) {
		return slots.size() > index ? slots.get(index) : ItemStack.EMPTY;
	}

	private static int dye(ItemStack stack, int fallback) {
		if (stack.isEmpty() || !stack.is(ItemTags.DYEABLE)) return fallback;
		DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
		return dyed == null ? fallback : ARGB.opaque(dyed.rgb());
	}

	private static Identifier texture(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, "textures/entity/rod/" + path + ".png");
	}

	private static List<ItemStack> rodInventory(ItemStack rod) {
		DataComponentType<ItemContainerContents> type = rodInventoryType();
		if (type == null) return List.of();
		ItemContainerContents contents = rod.get(type);
		return contents == null ? List.of() : contents.stream().toList();
	}

	@SuppressWarnings("unchecked")
	@Nullable
	private static DataComponentType<ItemContainerContents> rodInventoryType() {
		DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(ROD_INVENTORY_ID);
		return (DataComponentType<ItemContainerContents>) type;
	}

	@Nullable
	private static SoundEvent sound(Identifier id) {
		return BuiltInRegistries.SOUND_EVENT.getValue(id);
	}

	private static ResourceKey<LootTable> lootTable(String path) {
		return ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(MOD_ID, path));
	}
}
