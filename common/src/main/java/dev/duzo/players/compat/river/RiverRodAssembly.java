package dev.duzo.players.compat.river;

import com.riverfishing.component.ComponentSlot;
import com.riverfishing.component.RigType;
import com.riverfishing.component.RodType;
import com.riverfishing.item.BaitItem;
import com.riverfishing.item.HookItem;
import com.riverfishing.item.LineItem;
import com.riverfishing.item.ReelItem;
import com.riverfishing.item.RigItem;
import com.riverfishing.item.RodData;
import com.riverfishing.item.RodItem;
import com.riverfishing.rig.RigData;
import com.riverfishing.rig.RigLayout;
import com.riverfishing.rig.SlotRole;
import dev.duzo.players.Constants;
import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.api.requests.RaiseResult;
import dev.duzo.players.api.requests.RaisedRequest;
import dev.duzo.players.api.requests.RequestKey;
import dev.duzo.players.api.requests.RequesterKind;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.requests.PoolIndex;
import dev.duzo.players.entities.ai.requests.RequestRouting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Optional River classes are resolved only after the facade has checked that the mod is loaded. */
public final class RiverRodAssembly {

	private static final int BAIT_RESERVE = 16;
	private static List<Item> parts;
	private final Map<ResourceLocation, Order> orders = new LinkedHashMap<>();
	private final Map<ResourceLocation, Integer> reserve = new LinkedHashMap<>();
	private ResourceLocation bait;
	private ResourceLocation selectedRod;
	private ItemStack lastRod = ItemStack.EMPTY;
	private ItemStack rodSnapshot = ItemStack.EMPTY;
	private List<ItemStack> inventorySnapshot = List.of();
	private long nextCheck;
	private String announced = "";
	private boolean loggedFailure;
	private boolean loggedRequestFailure;
	private Preparation previous = new Preparation(false, "I need fishing tackle.");

	public record Preparation(boolean ready, String reason) {}

	private static final class Order {
		private final RequestKey key;
		private final long raisedAt;
		private final int target;
		private final boolean owned;
		private UUID holder;
		private boolean cancelling;

		private Order(RequestKey key, long raisedAt, int target, boolean owned, UUID holder) {
			this.key = key;
			this.raisedAt = raisedAt;
			this.target = target;
			this.owned = owned;
			this.holder = holder;
		}
	}

	public static boolean isRod(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof RodItem;
	}

	public static boolean isSupported(ItemStack stack) {
		if (!(stack.getItem() instanceof RodItem rod)) return false;
		return switch (rod.rodType()) {
			case STICK, BAMBOO, POLE, FEEDER, BOTTOM, CARP, SURF, SPINNING, ULTRALIGHT, SEA_SPIN -> true;
			default -> false;
		};
	}

	public Preparation prepare(FakePlayerEntity fisherman, ItemStack rod) {
		if (!(fisherman.level() instanceof ServerLevel level)) return new Preparation(false, "");
		if (!lastRod.isEmpty() && rod != lastRod) stop(fisherman);
		boolean changed = rod != lastRod || !ItemStack.matches(rodSnapshot, rod)
				|| !sameInventory(fisherman.getInventory(), inventorySnapshot);
		if (!changed && level.getGameTime() < nextCheck) return previous;
		lastRod = rod;
		selectedRod = id(rod);
		nextCheck = level.getGameTime() + 20;
		reserve.clear();
		Map<ResourceLocation, Integer> wanted = new LinkedHashMap<>();
		Preparation result;
		try {
			if (!isSupported(rod)) {
				result = new Preparation(false, "I can't use this fishing method yet.");
			} else {
				Plan plan = new Plan(rod, fisherman.getInventory(), candidates(level, fisherman));
				plan.assemble();
				if (plan.problem != null) {
					result = new Preparation(false, plan.problem);
				} else {
					wanted.putAll(plan.missing);
					reserve.putAll(plan.used);
					if (plan.bait != null) bait = plan.bait;
					else bait = null;
					if (plan.missing.isEmpty()) {
						// All external API work ran on copies. The only rod data those APIs change is custom data.
						if (!ItemStack.matches(plan.originalRod, rod)
								|| !sameInventory(fisherman.getInventory(), plan.originalInventory)) {
							return remember(fisherman, rod, new Preparation(false, "My tackle changed. I'll try again."));
						}
						rod.set(DataComponents.CUSTOM_DATA, plan.rod.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
						for (int i = 0; i < plan.inventory.size(); i++) {
							fisherman.getInventory().setItem(i, plan.inventory.get(i));
						}
						fisherman.getInventory().setChanged();
						reserve.clear();
						result = new Preparation(true, "");
					} else {
						result = new Preparation(false, "I need compatible parts for this rod.");
					}
					if (bait != null) {
						int assemblyNeed = result.ready() ? 0 : plan.used.getOrDefault(bait, 0);
						reserve.put(bait, BAIT_RESERVE + assemblyNeed);
						int shortage = BAIT_RESERVE + assemblyNeed - count(fisherman.getInventory(), bait);
						if (shortage > 0) wanted.merge(bait, shortage, Math::max);
					}
				}
			}
			loggedFailure = false;
		} catch (RuntimeException failure) {
			if (!loggedFailure) Constants.LOG.warn("Cannot assemble River Fishing tackle for {}", fisherman.getUUID(), failure);
			loggedFailure = true;
			result = new Preparation(false, "I couldn't assemble this rod. Check its tackle.");
		}
		try {
			updateOrders(level, fisherman, wanted);
			loggedRequestFailure = false;
		} catch (RuntimeException failure) {
			if (!loggedRequestFailure) Constants.LOG.warn("Cannot request River Fishing tackle for {}", fisherman.getUUID(), failure);
			loggedRequestFailure = true;
			// Assembly is already committed. A resupply problem does not undo a usable rod.
			if (!result.ready()) result = new Preparation(false, "I couldn't request these parts. Check my Quartermaster.");
		}
		if (result.ready()) announced = "";
		else if (!result.reason().equals(announced) && RequestRouting.notifyOwner(level, fisherman, result.reason())) {
			announced = result.reason();
		}
		return remember(fisherman, rod, result);
	}

	private Preparation remember(FakePlayerEntity fisherman, ItemStack rod, Preparation result) {
		rodSnapshot = rod.copy();
		inventorySnapshot = copyInventory(fisherman.getInventory());
		previous = result;
		return result;
	}

	/** Exact counts to retain, so a stack's excess can still go into the deposit chest. */
	public int[] reservedSlots(FakePlayerEntity fisherman, ItemStack rod) {
		SimpleContainer inventory = fisherman.getInventory();
		int[] kept = new int[inventory.getContainerSize()];
		if ((!lastRod.isEmpty() && rod != lastRod) || !id(rod).equals(selectedRod) || !isSupported(rod)) return kept;
		Map<ResourceLocation, Integer> remaining = new HashMap<>(reserve);
		for (int i = 0; i < kept.length; i++) {
			ItemStack stack = inventory.getItem(i);
			ResourceLocation id = id(stack);
			int amount = Math.min(stack.getCount(), remaining.getOrDefault(id, 0));
			kept[i] = amount;
			remaining.put(id, remaining.getOrDefault(id, 0) - amount);
		}
		return kept;
	}

	public void stop(FakePlayerEntity fisherman) {
		for (Order order : orders.values()) order.cancelling = true;
		if (fisherman.level() instanceof ServerLevel level) updateOrders(level, fisherman, Map.of());
		reserve.clear();
		bait = null;
		selectedRod = null;
		lastRod = ItemStack.EMPTY;
		rodSnapshot = ItemStack.EMPTY;
		inventorySnapshot = List.of();
		announced = "";
		nextCheck = 0;
	}

	private List<ItemStack> candidates(ServerLevel level, FakePlayerEntity fisherman) {
		if (parts == null) {
			parts = BuiltInRegistries.ITEM.stream().filter(item -> item instanceof ReelItem
					|| item instanceof LineItem || item instanceof RigItem || item instanceof HookItem
					|| item instanceof BaitItem).sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).toList();
		}
		Map<ResourceLocation, ItemStack> choices = new LinkedHashMap<>();
		// Preserve a pending choice across partial deliveries and empty storeroom refreshes.
		for (Order order : orders.values()) if (!order.cancelling) {
			Item item = BuiltInRegistries.ITEM.get(order.key.item());
			if (parts.contains(item)) choices.put(order.key.item(), new ItemStack(item));
		}
		if (bait != null) choices.putIfAbsent(bait, new ItemStack(BuiltInRegistries.ITEM.get(bait)));
		for (FakePlayerEntity qm : FakePlayerRequests.quartermasters(level, fisherman, fisherman.getAIState().ownerUUID())) {
			PoolIndex.of(level, qm).contents().entrySet().stream().filter(entry -> entry.getValue() > 0)
					.sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
					.forEach(entry -> {
						Item item = BuiltInRegistries.ITEM.get(entry.getKey());
						if (parts.contains(item)) choices.putIfAbsent(entry.getKey(), new ItemStack(item));
					});
		}
		for (Item item : parts) choices.putIfAbsent(BuiltInRegistries.ITEM.getKey(item), new ItemStack(item));
		return List.copyOf(choices.values());
	}

	private void updateOrders(ServerLevel level, FakePlayerEntity fisherman, Map<ResourceLocation, Integer> wanted) {
		var iterator = orders.entrySet().iterator();
		while (iterator.hasNext()) {
			Order order = iterator.next().getValue();
			if (!wanted.containsKey(order.key.item())) order.cancelling = true;
			FakePlayerEntity holder = RequestRouting.holderOf(level, fisherman, fisherman.getAIState().ownerUUID(), order.key);
			if (holder == null) {
				// An unloaded board is unknown, not evidence that its order was delivered.
				for (ServerLevel each : level.getServer().getAllLevels()) {
					if (each.getEntity(order.holder) instanceof FakePlayerEntity found) { holder = found; break; }
				}
				if (holder == null) continue;
			}
			if (RequestRouting.boardOf(holder) == null) continue;
			ItemRequest current = RequestRouting.snapshotOf(holder).find(order.key);
			if (current == null || current.raisedAt() != order.raisedAt) { iterator.remove(); continue; }
			order.holder = holder.getUUID();
			if (order.cancelling) {
				if (!order.owned || FakePlayerRequests.cancel(holder, order.key)) iterator.remove();
			} else if (!current.isOpen()) {
				// Retry a shortfall using the original lifetime target, never the smaller remaining count.
				FakePlayerRequests.raise(fisherman, new ItemStack(BuiltInRegistries.ITEM.get(order.key.item()), order.target), FakePlayerRequests.PRIORITY_FAKE);
			}
		}
		for (var need : wanted.entrySet()) {
			if (need.getValue() <= 0 || orders.containsKey(need.getKey())) continue;
			RaisedRequest raised = FakePlayerRequests.raise(fisherman,
					new ItemStack(BuiltInRegistries.ITEM.get(need.getKey()), need.getValue()), FakePlayerRequests.PRIORITY_FAKE);
			if (!raised.ok()) continue;
			ItemRequest request = raised.request();
			orders.put(need.getKey(), new Order(request.key(), request.raisedAt(), request.wanted(),
					raised.result() == RaiseResult.RAISED, raised.quartermaster().getUUID()));
		}
	}

	public CompoundTag save() {
		CompoundTag saved = new CompoundTag();
		if (bait != null) saved.putString("Bait", bait.toString());
		if (selectedRod != null) saved.putString("Rod", selectedRod.toString());
		CompoundTag kept = new CompoundTag();
		reserve.forEach((id, count) -> kept.putInt(id.toString(), count));
		saved.put("Reserve", kept);
		saved.putString("Announced", announced);
		ListTag pending = new ListTag();
		for (Order order : orders.values()) {
			CompoundTag tag = new CompoundTag();
			tag.put("Key", order.key.toNbt());
			tag.putLong("RaisedAt", order.raisedAt);
			tag.putInt("Target", order.target);
			tag.putBoolean("Owned", order.owned);
			tag.putUUID("Holder", order.holder);
			tag.putBoolean("Cancelling", order.cancelling);
			pending.add(tag);
		}
		saved.put("Orders", pending);
		return saved;
	}

	public void load(CompoundTag saved) {
		orders.clear();
		reserve.clear();
		bait = ResourceLocation.tryParse(saved.getString("Bait"));
		selectedRod = ResourceLocation.tryParse(saved.getString("Rod"));
		CompoundTag kept = saved.getCompound("Reserve");
		for (String key : kept.getAllKeys()) {
			ResourceLocation item = ResourceLocation.tryParse(key);
			if (item != null) reserve.put(item, Math.max(0, Math.min(BAIT_RESERVE + 1, kept.getInt(key))));
		}
		announced = saved.getString("Announced");
		ListTag pending = saved.getList("Orders", Tag.TAG_COMPOUND);
		for (int i = 0; i < pending.size(); i++) {
			CompoundTag tag = pending.getCompound(i);
			RequestKey key = RequestKey.fromNbt(tag.getCompound("Key"));
			if (key == null || key.kind() != RequesterKind.FAKE || !tag.hasUUID("Holder")) continue;
			Order order = new Order(key, tag.getLong("RaisedAt"), Math.max(1, Math.min(ItemRequest.MAX_COUNT, tag.getInt("Target"))),
					tag.getBoolean("Owned"), tag.getUUID("Holder"));
			order.cancelling = tag.getBoolean("Cancelling");
			orders.put(key.item(), order);
		}
		lastRod = ItemStack.EMPTY;
		rodSnapshot = ItemStack.EMPTY;
		inventorySnapshot = List.of();
		nextCheck = 0;
	}

	private static ResourceLocation id(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem());
	}

	private static int count(SimpleContainer inventory, ResourceLocation item) {
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) if (id(inventory.getItem(i)).equals(item)) count += inventory.getItem(i).getCount();
		return count;
	}

	private static List<ItemStack> copyInventory(SimpleContainer inventory) {
		List<ItemStack> copy = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) copy.add(inventory.getItem(i).copy());
		return copy;
	}

	private static boolean sameInventory(SimpleContainer inventory, List<ItemStack> expected) {
		if (inventory.getContainerSize() != expected.size()) return false;
		for (int i = 0; i < expected.size(); i++) if (!ItemStack.matches(inventory.getItem(i), expected.get(i))) return false;
		return true;
	}

	private static final class Plan {
		private final ItemStack originalRod;
		private final List<ItemStack> originalInventory;
		private final ItemStack rod;
		private final List<ItemStack> inventory;
		private final List<ItemStack> remote;
		private final Map<ResourceLocation, Integer> missing = new LinkedHashMap<>();
		private final Map<ResourceLocation, Integer> used = new LinkedHashMap<>();
		private ResourceLocation bait;
		private String problem;

		private Plan(ItemStack original, SimpleContainer supplies, List<ItemStack> remote) {
			originalRod = original.copy();
			rod = original.copy();
			originalInventory = copyInventory(supplies);
			inventory = copyInventory(supplies);
			this.remote = remote;
		}

		private void assemble() {
			RodType type = ((RodItem) rod.getItem()).rodType();
			ItemStack reel = RodData.get(rod, ComponentSlot.REEL);
			ItemStack line = RodData.get(rod, ComponentSlot.LINE);
			ItemStack rig = RodData.get(rod, ComponentSlot.RIG);
			if ((!reel.isEmpty() && (!(reel.getItem() instanceof ReelItem r) || !type.acceptsReel(r)))
					|| (!line.isEmpty() && !(line.getItem() instanceof LineItem))
					|| (!rig.isEmpty() && (!(rig.getItem() instanceof RigItem r)
							|| (type.nativeRig() != null && r.rigType() != type.nativeRig())
							|| (type.nativeRig() == null && !bottomRig(r.rigType()))))) {
				problem = "This rod has incompatible tackle fitted.";
				return;
			}
			if (type.takesReel()) {
				List<ItemStack> reels = reel.isEmpty() ? choices(stack -> stack.getItem() instanceof ReelItem r && type.acceptsReel(r)) : List.of(reel);
				List<ItemStack> lines = line.isEmpty() ? choices(stack -> stack.getItem() instanceof LineItem) : List.of(line);
				ItemStack chosenReel = ItemStack.EMPTY, chosenLine = ItemStack.EMPTY;
				int bestCost = Integer.MAX_VALUE;
				for (ItemStack r : reels) for (ItemStack l : lines) {
					if (!((ReelItem) r.getItem()).acceptsLine((LineItem) l.getItem())) continue;
					int cost = (reel.isEmpty() && !have(r) ? 1 : 0) + (line.isEmpty() && !have(l) ? 1 : 0);
					if (cost < bestCost) { chosenReel = r; chosenLine = l; bestCost = cost; }
				}
				if (chosenReel.isEmpty()) { problem = "This rod's reel and line don't fit."; return; }
				if (reel.isEmpty()) RodData.set(rod, ComponentSlot.REEL, take(chosenReel));
				if (line.isEmpty()) RodData.set(rod, ComponentSlot.LINE, take(chosenLine));
			} else if (line.isEmpty()) {
				RodData.set(rod, ComponentSlot.LINE, choose(stack -> stack.getItem() instanceof LineItem));
			}
			if (rig.isEmpty()) {
				if (type.nativeRig() != null) {
					RodData.ensureNativeRig(rod, type);
					rig = RodData.get(rod, ComponentSlot.RIG);
				} else rig = choose(stack -> stack.getItem() instanceof RigItem r && bottomRig(r.rigType()));
			}
			if (rig.isEmpty()) { problem = "I need a compatible rig for this rod."; return; }
			SlotRole[] roles = RigLayout.rolesFor(RigData.rigType(rig));
			NonNullList<ItemStack> contents = RigData.load(rig);
			for (int i = 0; i < contents.size(); i++) {
				if (!contents.get(i).isEmpty() && !roles[i].accepts(contents.get(i))) {
					problem = "This rig has incompatible tackle fitted.";
					return;
				}
			}
			if (type.activeRetrieve()) {
				fillOne(contents, roles, SlotRole.LURE, stack -> stack.getItem() instanceof BaitItem b && b.artificial());
			} else {
				fillOne(contents, roles, SlotRole.HOOK, stack -> stack.getItem() instanceof HookItem);
				fillOne(contents, roles, SlotRole.BAIT, stack -> stack.getItem() instanceof BaitItem b && !b.artificial() && !b.baitId().equals("livebait"));
			}
			for (int i = 0; i < contents.size(); i++) {
				ItemStack stack = contents.get(i);
				if ((roles[i] == SlotRole.BAIT || roles[i] == SlotRole.LURE) && stack.getItem() instanceof BaitItem b
						&& !b.artificial() && !b.baitId().equals("livebait")) { bait = id(stack); break; }
			}
			RigData.save(rig, contents);
			RodData.set(rod, ComponentSlot.RIG, rig);
			if (!RodData.isAssembled(rod)) problem = "I need compatible parts for this rod.";
		}

		private void fillOne(NonNullList<ItemStack> contents, SlotRole[] roles, SlotRole role, Predicate<ItemStack> accepts) {
			for (int i = 0; i < roles.length; i++) if (roles[i] == role && !contents.get(i).isEmpty()) return;
			for (int i = 0; i < roles.length; i++) if (roles[i] == role) {
				ItemStack selected = choose(stack -> role.accepts(stack) && accepts.test(stack));
				if (selected.isEmpty()) problem = "I need a compatible hook and bait or lure for this rod.";
				else contents.set(i, selected);
				return;
			}
			problem = "This rig doesn't suit this rod.";
		}

		private static boolean bottomRig(RigType type) {
			return switch (type) {
				case FEEDER, FLAT_FEEDER, GROUND, CARP, GRUSHA, CATFISH -> true;
				default -> false;
			};
		}

		private List<ItemStack> choices(Predicate<ItemStack> accepts) {
			List<ItemStack> candidates = new ArrayList<>();
			for (ItemStack stack : inventory) if (!stack.isEmpty() && accepts.test(stack)) candidates.add(stack.copyWithCount(1));
			for (ItemStack stack : remote) if (accepts.test(stack)) candidates.add(stack);
			return candidates;
		}

		private ItemStack choose(Predicate<ItemStack> accepts) {
			List<ItemStack> candidates = choices(accepts);
			return candidates.isEmpty() ? ItemStack.EMPTY : take(candidates.getFirst());
		}

		private boolean have(ItemStack wanted) {
			for (ItemStack stack : inventory) if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, wanted)) return true;
			return false;
		}

		private ItemStack take(ItemStack wanted) {
			used.merge(id(wanted), 1, Integer::sum);
			for (ItemStack stack : inventory) if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, wanted)) return stack.split(1);
			missing.merge(id(wanted), 1, Integer::sum);
			return wanted.copyWithCount(1);
		}
	}
}
