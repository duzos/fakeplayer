package dev.duzo.players.compat;

import dev.duzo.players.entities.ai.Tackle;
import dev.duzo.players.entities.ai.TackleLook;
import dev.duzo.players.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Aquaculture support, deliberately per-mod and deliberately not in {@code common}: Aquaculture has no
 * Fabric build. Nothing here compiles against it.
 *
 * <p>This is the pre-component variant. On 1.20.x the rod's tackle is a Forge {@code ItemStackHandler}
 * serialised into the stack's {@code Inventory} tag rather than a data component, so the slots are read
 * and written as raw NBT. Slot 0 is the hook, 1 the bait, 2 the line, 3 the bobber, and every hook's
 * numbers are read off its item id, so the whole module is vanilla API plus string ids and degrades to
 * {@link Tackle#PLAIN} when the mod is absent.
 *
 * <p>Their own {@code AquaFishingRodItem.use()} rejects a FakePlayer outright, so none of this goes
 * through their use path; the Fake Players bobber is driven directly instead.
 */
public final class AquacultureCompat {
	private AquacultureCompat() {}

	private static final String MOD_ID = "aquaculture";
	/** The separate addon that turns on the lava fluid for lava capable hooks. It has no 1.20 build. */
	private static final String LAVA_ADDON_ID = "aq2lava";

	private static final String INVENTORY_TAG = "Inventory";
	private static final String ITEMS_TAG = "Items";
	private static final String SLOT_TAG = "Slot";

	private static final ResourceLocation NOTE_CATCH_SOUND_ID = new ResourceLocation(MOD_ID, "bobber_note_catch");
	private static final ResourceLocation NEPTUNIUM_ROD_ID = new ResourceLocation(MOD_ID, "neptunium_fishing_rod");

	private static final ResourceLocation LAVA_TABLE = new ResourceLocation(MOD_ID, "gameplay/fishing/lava/fishing");
	private static final ResourceLocation NETHER_TABLE = new ResourceLocation(MOD_ID, "gameplay/fishing/nether/fishing");

	private static final int HOOK_SLOT = 0;
	private static final int BAIT_SLOT = 1;
	private static final int LINE_SLOT = 2;
	private static final int BOBBER_SLOT = 3;

	private static final ResourceLocation DEFAULT_HOOK_TEXTURE = texture("hook/hook");
	private static final ResourceLocation BOBBER_TEXTURE = texture("bobber/bobber");
	private static final ResourceLocation BOBBER_OVERLAY_TEXTURE = texture("bobber/bobber_overlay");
	private static final ResourceLocation BOBBER_VANILLA_TEXTURE = texture("bobber/bobber_vanilla");
	/** The red their renderer tints an unfitted bobber with. */
	private static final int DEFAULT_BOBBER_COLOR = 0xFFC12626;

	/** Aquaculture's own hook numbers. The lava hooks come from an addon with no 1.20 build. */
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

		ItemStack hookStack = slot(rod, HOOK_SLOT);
		ResourceLocation fitted = null;
		if (!hookStack.isEmpty()) {
			ResourceLocation id = BuiltInRegistries.ITEM.getKey(hookStack.getItem());
			if (id != null && MOD_ID.equals(id.getNamespace())) fitted = id;
		}
		HookStats hook = fitted == null ? null : HOOKS.get(fitted.getPath());

		// Bait is only ever an IBaitItem, which the slot enforces, and every bait Aquaculture ships gives the
		// same one step of lure speed. Reading the interface would need a compile dependency on a mod that has
		// no Fabric build and no release for some of these versions, so the shipped value is assumed instead.
		int lure = slot(rod, BAIT_SLOT).isEmpty() ? 0 : 1;
		ResourceLocation rodId = BuiltInRegistries.ITEM.getKey(rod.getItem());
		if (NEPTUNIUM_ROD_ID.equals(rodId)) lure += 1;

		boolean ours = rodId != null && MOD_ID.equals(rodId.getNamespace());
		TackleLook look = ours ? look(rod, fitted) : TackleLook.PLAIN;

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

	/** Damage or shrink the fitted bait, after a catch that landed something. */
	public static void consumeBait(ServerLevel level, ItemStack rod) {
		ItemStack bait = slot(rod, BAIT_SLOT);
		if (bait.isEmpty()) return;

		if (bait.isDamageableItem()) {
			// Counted down by hand rather than through ItemStack.hurt. Their own code shrinks the stack on
			// break without clearing the damage, so every later catch breaks another bait and a stack is gone
			// in a fraction of the casts it should last.
			bait.setDamageValue(bait.getDamageValue() + 1);
			if (bait.getDamageValue() >= bait.getMaxDamage()) {
				bait.shrink(1);
				bait.setDamageValue(0);
			}
		} else {
			bait.shrink(1);
		}
		setSlot(rod, BAIT_SLOT, bait);
	}

	/** Mirrors their renderer: a bobber layer tinted by its dye, then the hook's own texture over it. */
	private static TackleLook look(ItemStack rod, @Nullable ResourceLocation fittedHook) {
		ResourceLocation hookTexture = fittedHook == null
				? DEFAULT_HOOK_TEXTURE
				: texture("hook/" + fittedHook.getPath());

		ItemStack bobber = slot(rod, BOBBER_SLOT);
		boolean hasBobber = !bobber.isEmpty();

		return new TackleLook(
				hookTexture,
				hasBobber ? BOBBER_TEXTURE : null,
				hasBobber ? BOBBER_OVERLAY_TEXTURE : BOBBER_VANILLA_TEXTURE,
				hasBobber ? dye(bobber, DEFAULT_BOBBER_COLOR) : DEFAULT_BOBBER_COLOR,
				dye(slot(rod, LINE_SLOT), 0xFF000000));
	}

	/** Leather dye lives in the display tag before data components. */
	private static int dye(ItemStack stack, int fallback) {
		if (stack.isEmpty()) return fallback;
		CompoundTag display = stack.getTagElement("display");
		return display != null && display.contains("color", Tag.TAG_INT)
				? 0xFF000000 | display.getInt("color")
				: fallback;
	}

	/** One slot of the rod's serialised ItemStackHandler, or empty. */
	private static ItemStack slot(ItemStack rod, int index) {
		ListTag items = itemsTag(rod);
		if (items == null) return ItemStack.EMPTY;
		for (int i = 0; i < items.size(); i++) {
			CompoundTag entry = items.getCompound(i);
			if (entry.getInt(SLOT_TAG) == index) return ItemStack.of(entry);
		}
		return ItemStack.EMPTY;
	}

	private static void setSlot(ItemStack rod, int index, ItemStack stack) {
		ListTag items = itemsTag(rod);
		if (items == null) return;
		for (int i = 0; i < items.size(); i++) {
			CompoundTag entry = items.getCompound(i);
			if (entry.getInt(SLOT_TAG) != index) continue;
			CompoundTag written = new CompoundTag();
			if (!stack.isEmpty()) {
				stack.save(written);
				written.putInt(SLOT_TAG, index);
			}
			items.set(i, written);
			return;
		}
	}

	@Nullable
	private static ListTag itemsTag(ItemStack rod) {
		if (!rod.hasTag() || rod.getTag() == null) return null;
		CompoundTag tag = rod.getTag();
		if (!tag.contains(INVENTORY_TAG, Tag.TAG_COMPOUND)) return null;
		CompoundTag inventory = tag.getCompound(INVENTORY_TAG);
		return inventory.contains(ITEMS_TAG, Tag.TAG_LIST) ? inventory.getList(ITEMS_TAG, Tag.TAG_COMPOUND) : null;
	}

	@Nullable
	private static SoundEvent sound(ResourceLocation id) {
		return BuiltInRegistries.SOUND_EVENT.get(id);
	}

	private static ResourceLocation texture(String path) {
		return new ResourceLocation(MOD_ID, "textures/entity/rod/" + path + ".png");
	}
}
