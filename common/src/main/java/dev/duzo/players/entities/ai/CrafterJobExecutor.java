package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CrafterJobExecutor implements JobExecutor {
	private static final double SPEED = 1.0;
	// How many sets' worth of each ingredient to buffer before a crafting run.
	private static final int BATCH = 8;
	// Ticks per ingredient placed into the grid (0.1s at 20 tps).
	private static final int PLACE_TICKS = 2;

	private enum Phase { TO_SOURCE, PULL, TO_TABLE, CRAFT, TO_DEPOSIT, DUMP }

	private Phase phase = Phase.TO_SOURCE;
	private int craftIndex;
	private int craftTimer = PLACE_TICKS;
	// the real main-hand item, stashed while the hand instead holds the visual "placing an ingredient" stack
	private ItemStack heldMainHand = ItemStack.EMPTY;
	private boolean handStashed = false;
	// heldMainHand encoded to NBT so serialize() can persist it without needing entity/registry access;
	// kept in sync whenever heldMainHand changes
	private CompoundTag heldMainHandTag = new CompoundTag();
	// set by deserialize() when a stash was persisted; heldMainHand is decoded from heldMainHandTag lazily,
	// the first time entity/registry access is available (deserialize() itself has neither)
	private boolean needsHandRestore = false;

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		restoreHandIfNeeded(entity);
		AIState state = entity.getAIState();
		BlockPos source = state.sourceChest();
		BlockPos deposit = state.depositChest();
		BlockPos table = state.waypoint();
		if (source == null || deposit == null || table == null) return; // needs source, table and deposit

		CompoundTag recipe = state.jobParams().getCompoundOrEmpty("Recipe");
		if (recipe.isEmpty()) return; // nothing taught yet

		List<Item> placeOrder = readPlaceOrder(recipe);
		if (placeOrder.isEmpty()) return;
		Map<Item, Integer> need = new LinkedHashMap<>();
		for (Item it : placeOrder) need.merge(it, 1, Integer::sum);
		ItemStack out = readOut(recipe, entity);
		if (out.isEmpty()) return;

		SimpleContainer inv = entity.getInventory();

		// Only the source/deposit polling phases hold a container open; everything else closes it.
		if (phase != Phase.PULL && phase != Phase.DUMP) JobHelpers.closeContainer(level, entity);

		switch (phase) {
			case TO_SOURCE -> {
				if (JobHelpers.walkTo(entity, source, SPEED) == JobHelpers.WalkResult.ARRIVED) phase = Phase.PULL;
			}
			case PULL -> {
				Container src = JobHelpers.containerAt(level, source);
				if (src == null) {
					JobHelpers.closeContainer(level, entity);
					entity.getNavigation().stop();
					if (hasOutputs(inv, need)) phase = Phase.TO_DEPOSIT;
					return;
				}
				if (entity.blockPosition().distSqr(source) > JobHelpers.ARRIVE_SQR) { JobHelpers.closeContainer(level, entity); phase = Phase.TO_SOURCE; return; }
				if (!JobHelpers.pollContainer(level, entity, source)) return; // open + pause ~1s before pulling
				int moved = JobHelpers.inventoryFull(entity) ? 0 : pullNeeded(src, inv, need);
				if (moved == 0) {
					if (hasFullSet(inv, need)) phase = Phase.TO_TABLE;
					else if (hasOutputs(inv, need)) phase = Phase.TO_DEPOSIT;
					else entity.getNavigation().stop(); // source dry, nothing to craft or bank: idle and poll
				}
			}
			case TO_TABLE -> {
				if (JobHelpers.walkTo(entity, table, SPEED) == JobHelpers.WalkResult.ARRIVED) {
					if (!craftingTableNear(level, table)) { entity.getNavigation().stop(); return; } // no table here: idle
					if (!handStashed) {
						heldMainHand = entity.getMainHandItem().copy();
						handStashed = true;
						heldMainHandTag = encodeMainHand(entity, heldMainHand);
					}
					craftIndex = 0;
					craftTimer = PLACE_TICKS;
					phase = Phase.CRAFT;
				}
			}
			case CRAFT -> {
				if (entity.blockPosition().distSqr(table) > JobHelpers.ARRIVE_SQR) { phase = Phase.TO_TABLE; return; }
				if (!hasFullSet(inv, need)) { clearHand(entity, need); phase = Phase.TO_DEPOSIT; return; }
				if (--craftTimer > 0) return;
				if (craftIndex < placeOrder.size()) {
					// Hold the ingredient being placed and swing, one cell every 0.1s.
					entity.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(placeOrder.get(craftIndex)));
					entity.swing(InteractionHand.MAIN_HAND);
					craftIndex++;
					craftTimer = PLACE_TICKS;
					return;
				}
				// Whole grid placed: assemble one result, then craft another set or go deposit.
				consumeSet(inv, need);
				ItemStack rem = inv.addItem(out.copy());
				if (!rem.isEmpty()) entity.spawnAtLocation(level, rem);
				craftIndex = 0;
				if (hasFullSet(inv, need) && JobHelpers.canAccept(inv, out)) {
					craftTimer = PLACE_TICKS;
				} else {
					clearHand(entity, need);
					phase = Phase.TO_DEPOSIT;
				}
			}
			case TO_DEPOSIT -> {
				if (JobHelpers.walkTo(entity, deposit, SPEED) == JobHelpers.WalkResult.ARRIVED) phase = Phase.DUMP;
			}
			case DUMP -> {
				Container dst = JobHelpers.containerAt(level, deposit);
				if (dst == null) { JobHelpers.closeContainer(level, entity); phase = Phase.TO_SOURCE; return; }
				if (entity.blockPosition().distSqr(deposit) > JobHelpers.ARRIVE_SQR) { JobHelpers.closeContainer(level, entity); phase = Phase.TO_DEPOSIT; return; }
				if (!JobHelpers.pollContainer(level, entity, deposit)) return; // open + pause ~1s before depositing
				int moved = dumpOutputs(inv, dst, need);
				if (moved == 0) phase = Phase.TO_SOURCE;
			}
		}
	}

	/** A crafting table at, or directly adjacent to, the marked spot. */
	private boolean craftingTableNear(ServerLevel level, BlockPos center) {
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int dz = -1; dz <= 1; dz++)
					if (level.getBlockState(center.offset(dx, dy, dz)).is(Blocks.CRAFTING_TABLE)) return true;
		return false;
	}

	/** Restores whatever the main hand really held before crafting started overwriting it with visual stacks. */
	private void clearHand(FakePlayerEntity entity, Map<Item, Integer> need) {
		if (handStashed) {
			ItemStack current = entity.getMainHandItem();
			// only overwrite what's plausibly still ours (empty, or a leftover ingredient placeholder) - if
			// something else put a different item in the main hand meanwhile, don't clobber it, bank the stash
			if (current.isEmpty() || need.containsKey(current.getItem())) {
				entity.setItemSlot(EquipmentSlot.MAINHAND, heldMainHand);
			} else if (!heldMainHand.isEmpty()) {
				ItemStack rem = entity.getInventory().addItem(heldMainHand.copy());
				if (!rem.isEmpty()) entity.spawnAtLocation((ServerLevel) entity.level(), rem);
			}
		}
		heldMainHand = ItemStack.EMPTY;
		handStashed = false;
		heldMainHandTag = new CompoundTag();
		needsHandRestore = false;
	}

	/** The recipe's ingredient tally, recomputed from job params - used where tick()'s local copy isn't in scope. */
	private Map<Item, Integer> currentNeed(FakePlayerEntity entity) {
		CompoundTag recipe = entity.getAIState().jobParams().getCompoundOrEmpty("Recipe");
		Map<Item, Integer> need = new LinkedHashMap<>();
		for (Item it : readPlaceOrder(recipe)) need.merge(it, 1, Integer::sum);
		return need;
	}

	/** Decodes heldMainHand from the persisted tag once entity/registry access is available. */
	private void restoreHandIfNeeded(FakePlayerEntity entity) {
		if (!needsHandRestore) return;
		heldMainHand = decodeMainHand(entity, heldMainHandTag);
		needsHandRestore = false;
	}

	private CompoundTag encodeMainHand(FakePlayerEntity entity, ItemStack stack) {
		if (stack.isEmpty()) return new CompoundTag();
		var ops = entity.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
		if (encoded == null) return new CompoundTag();
		CompoundTag holder = new CompoundTag();
		holder.put("Stack", encoded);
		return holder.getCompoundOrEmpty("Stack");
	}

	private ItemStack decodeMainHand(FakePlayerEntity entity, CompoundTag stackTag) {
		if (stackTag == null || stackTag.isEmpty()) return ItemStack.EMPTY;
		var ops = entity.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		return ItemStack.CODEC.parse(ops, stackTag).result().orElse(ItemStack.EMPTY);
	}

	/** The learned grid as an ordered list of items, one per filled cell. */
	private List<Item> readPlaceOrder(CompoundTag recipe) {
		List<Item> order = new ArrayList<>();
		ListTag grid = recipe.getListOrEmpty("Grid");
		for (int i = 0; i < grid.size(); i++) {
			String id = grid.getStringOr(i, "");
			if (id.isEmpty()) continue;
			Identifier rid = Identifier.tryParse(id);
			if (rid == null) continue;
			Item item = BuiltInRegistries.ITEM.getValue(rid);
			if (item == Items.AIR) continue;
			order.add(item);
		}
		return order;
	}

	private ItemStack readOut(CompoundTag recipe, FakePlayerEntity entity) {
		CompoundTag outTag = recipe.getCompoundOrEmpty("Out");
		if (outTag.isEmpty()) return ItemStack.EMPTY;
		var ops = entity.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		return ItemStack.CODEC.parse(ops, outTag).result().orElse(ItemStack.EMPTY);
	}

	private boolean hasFullSet(SimpleContainer inv, Map<Item, Integer> need) {
		for (Map.Entry<Item, Integer> e : need.entrySet())
			if (countItem(inv, e.getKey()) < e.getValue()) return false;
		return true;
	}

	private boolean hasOutputs(SimpleContainer inv, Map<Item, Integer> need) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && !need.containsKey(s.getItem())) return true;
		}
		return false;
	}

	private int countItem(SimpleContainer inv, Item item) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && s.getItem() == item) n += s.getCount();
		}
		return n;
	}

	private void consumeSet(SimpleContainer inv, Map<Item, Integer> need) {
		for (Map.Entry<Item, Integer> e : need.entrySet()) {
			int remaining = e.getValue();
			for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty() || s.getItem() != e.getKey()) continue;
				int take = Math.min(remaining, s.getCount());
				s.shrink(take);
				remaining -= take;
				if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
			}
		}
	}

	/** Pull one source stack of a needed ingredient that we have not yet buffered a batch of. */
	private int pullNeeded(Container src, SimpleContainer inv, Map<Item, Integer> need) {
		for (int i = 0; i < src.getContainerSize(); i++) {
			ItemStack stack = src.getItem(i);
			if (stack.isEmpty()) continue;
			Integer per = need.get(stack.getItem());
			if (per == null) continue;
			if (countItem(inv, stack.getItem()) >= per * BATCH) continue;
			ItemStack remainder = HopperBlockEntity.addItem(src, inv, stack.copy(), null);
			int taken = stack.getCount() - remainder.getCount();
			if (taken > 0) {
				stack.shrink(taken);
				if (stack.isEmpty()) src.setItem(i, ItemStack.EMPTY);
				src.setChanged();
				inv.setChanged();
				return 1;
			}
		}
		return 0;
	}

	/** Push one stack of anything that is not a recipe ingredient (the crafted output, plus container leftovers). */
	private int dumpOutputs(SimpleContainer inv, Container dst, Map<Item, Integer> need) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || need.containsKey(stack.getItem())) continue;
			ItemStack remainder = HopperBlockEntity.addItem(inv, dst, stack.copy(), null);
			int moved = stack.getCount() - remainder.getCount();
			if (moved > 0) {
				stack.shrink(moved);
				if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
				inv.setChanged();
				dst.setChanged();
				return 1;
			}
		}
		return 0;
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		restoreHandIfNeeded(entity);
		entity.getNavigation().stop();
		clearHand(entity, currentNeed(entity));
		if (entity.level() instanceof ServerLevel sl) JobHelpers.closeContainer(sl, entity);
	}

	@Override
	public void onResume(FakePlayerEntity entity) {}

	@Override
	public boolean lockMainHand() { return handStashed; }

	@Override
	public CompoundTag serialize() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Phase", phase.name());
		tag.putBoolean("HandStashed", handStashed);
		tag.put("HeldMainHand", heldMainHandTag);
		return tag;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) return;
		String name = tag.getStringOr("Phase", "");
		if (!name.isEmpty()) {
			try { phase = Phase.valueOf(name); } catch (IllegalArgumentException ignored) {}
		}
		handStashed = tag.getBooleanOr("HandStashed", false);
		heldMainHandTag = tag.getCompoundOrEmpty("HeldMainHand");
		heldMainHand = ItemStack.EMPTY;
		needsHandRestore = handStashed; // decode lazily once entity/registry access is available
	}
}
