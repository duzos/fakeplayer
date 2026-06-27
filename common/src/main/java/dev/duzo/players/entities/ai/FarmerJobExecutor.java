package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class FarmerJobExecutor implements JobExecutor {
	public enum Phase { SCANNING, COLLECTING_DROPS, WORKING, RETURNING, WAITING_AT_CHEST }
	private enum Action { TILL, WATER, PLANT, BONEMEAL, HARVEST }

	private static final int SCAN_BUDGET = 8192;
	private static final int ACTION_COOLDOWN_TICKS = 6;
	private static final int MAX_PATH_FAIL = 3;
	private static final int RETRY_WAIT_TICKS = 20 * 15;
	private static final int IDLE_WAIT_TICKS = 20 * 30;
	private static final int SEED_RESERVE = 64;
	private static final int BONEMEAL_RESERVE = 64;
	private static final int DURABILITY_RESERVE = 8;
	private static final int WATER_RADIUS = 4;
	private static final double VACUUM_RADIUS = 4.0;
	private static final double ITEM_REACH_SQR = 4.0;
	private static final double STAND_RANGE = 1.6;

	private Phase phase = Phase.SCANNING;
	private Action action;
	private BlockPos target;
	private BlockPos actionStand;
	private int pathFailCount = 0;
	private int actionCooldown = 0;
	private long waitUntilTick = 0L;
	private String lastBlocker = "";

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		JobHelpers.vacuum(level, entity, VACUUM_RADIUS);
		if (actionCooldown > 0) actionCooldown--;

		// field-work phases never touch a container; only chest phases re-open it inside serviceAtChest
		if (phase == Phase.WORKING || phase == Phase.COLLECTING_DROPS) JobHelpers.closeContainer(level, entity);

		switch (phase) {
			case SCANNING -> tickScanning(level, entity);
			case COLLECTING_DROPS -> tickCollectingDrops(level, entity);
			case WORKING -> tickWorking(level, entity);
			case RETURNING -> tickReturning(level, entity);
			case WAITING_AT_CHEST -> tickWaitingAtChest(level, entity);
		}
	}

	private void tickScanning(ServerLevel level, FakePlayerEntity entity) {
		AIState s = entity.getAIState();
		BlockPos a = s.regionA();
		BlockPos b = s.regionB();
		if (a == null || b == null) {
			waitForBlocker(level, entity, "farmer: no region set");
			return;
		}
		if (JobHelpers.inventoryFull(entity)) {
			phase = Phase.RETURNING;
			return;
		}
		if (nearestRegionDrop(level, entity) != null) {
			phase = Phase.COLLECTING_DROPS;
			pathFailCount = 0;
			return;
		}

		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());

		boolean hasHoe = hasUsableHoe(entity);
		boolean hasWater = hasWaterBucket(entity);
		boolean hasSeed = findSeed(entity) != null;
		boolean hasBonemeal = bonemealCount(entity) > 0;

		// optimal irrigation grid: one source per 9x9 tile (each hydrates +/-4), edge-clamped to the region
		Set<Integer> waterX = axisAnchors(x0, x1);
		Set<Integer> waterZ = axisAnchors(z0, z1);

		BlockPos origin = entity.blockPosition();
		BlockPos harvest = null, water = null, till = null, plant = null, bonemeal = null;
		double dHarvest = Double.MAX_VALUE, dWater = dHarvest, dTill = dHarvest, dPlant = dHarvest, dBonemeal = dHarvest;
		int budget = SCAN_BUDGET;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		outer:
		// crops sit one layer above tilled soil, so sweep one block above the marked box too
		for (int y = y0; y <= y1 + 1; y++)
		for (int x = x0; x <= x1; x++)
		for (int z = z0; z <= z1; z++) {
			if (--budget <= 0) break outer;
			cursor.set(x, y, z);
			BlockState state = level.getBlockState(cursor);

			if (state.getBlock() instanceof CropBlock crop) {
				double d = cursor.distSqr(origin);
				if (crop.isMaxAge(state)) {
					if (d < dHarvest) { dHarvest = d; harvest = cursor.immutable(); }
				} else if (hasBonemeal && d < dBonemeal) {
					dBonemeal = d; bonemeal = cursor.immutable();
				}
				continue;
			}
			if (y > y1) continue; // the extra top layer only ever holds crops

			boolean farmland = state.is(Blocks.FARMLAND);
			boolean tillable = isTillable(state);
			if (!farmland && !tillable) continue;
			if (!level.getBlockState(cursor.above()).isAir()) continue; // never drown a planted cell

			double d = cursor.distSqr(origin);
			if (hasWater && waterX.contains(x) && waterZ.contains(z)) {
				// designated irrigation cell - drop a source straight into the soil
				if (d < dWater) { dWater = d; water = cursor.immutable(); }
			} else if (farmland) {
				BlockPos cropPos = cursor.above().immutable();
				if (hasSeed && plantableAt(level, entity, cropPos) && d < dPlant) { dPlant = d; plant = cropPos; }
			} else if (hasHoe) {
				if (d < dTill) { dTill = d; till = cursor.immutable(); }
			}
		}

		// water grid first so freshly tilled farmland is hydrated and never reverts to dirt
		if (harvest != null) { begin(Action.HARVEST, harvest); return; }
		if (water != null) { begin(Action.WATER, water); return; }
		if (till != null) { begin(Action.TILL, till); return; }
		if (plant != null) { begin(Action.PLANT, plant); return; }
		if (bonemeal != null) { begin(Action.BONEMEAL, bonemeal); return; }

		if (!atChest(entity)) {
			phase = Phase.RETURNING;
			return;
		}
		serviceAtChest(level, entity);
		waitAtChest(level, entity, IDLE_WAIT_TICKS);
	}

	private void begin(Action a, BlockPos pos) {
		action = a;
		target = pos;
		actionStand = null;
		pathFailCount = 0;
		lastBlocker = ""; // making progress again - let the next problem re-announce
		phase = Phase.WORKING;
	}

	private void tickWorking(ServerLevel level, FakePlayerEntity entity) {
		if (action == null || target == null || !stillValid(level, entity)) {
			clearTarget();
			phase = Phase.SCANNING;
			return;
		}
		BlockPos soil = action == Action.TILL || action == Action.WATER ? target : target.below();
		if (actionStand == null || !canStandAt(level, actionStand)) actionStand = findStandNear(level, soil);
		if (actionStand == null) {
			waitForBlocker(level, entity, "farmer: no stand position");
			return;
		}
		if (!near(entity, actionStand, STAND_RANGE)) {
			entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
			if (entity.getNavigation().isDone()) {
				boolean ok = entity.getNavigation().moveTo(actionStand.getX() + 0.5, actionStand.getY(), actionStand.getZ() + 0.5, 1.0);
				if (!ok && ++pathFailCount >= MAX_PATH_FAIL) waitForBlocker(level, entity, "farmer: pathing failed");
			}
			return;
		}
		entity.getNavigation().stop();
		if (actionCooldown > 0) return;
		perform(level, entity);
		actionCooldown = ACTION_COOLDOWN_TICKS;
		clearTarget();
		phase = Phase.SCANNING;
	}

	private boolean stillValid(ServerLevel level, FakePlayerEntity entity) {
		BlockState state = level.getBlockState(target);
		return switch (action) {
			case HARVEST -> state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
			case BONEMEAL -> state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state) && bonemealCount(entity) > 0;
			case TILL -> isTillable(state) && level.getBlockState(target.above()).isAir() && hasUsableHoe(entity);
			case WATER -> (state.is(Blocks.FARMLAND) || isTillable(state)) && level.getBlockState(target.above()).isAir()
					&& hasWaterBucket(entity);
			case PLANT -> state.isAir() && level.getBlockState(target.below()).is(Blocks.FARMLAND)
					&& findPlantableSeed(level, entity, target) != null;
		};
	}

	private void perform(ServerLevel level, FakePlayerEntity entity) {
		lookAndSwing(entity, target);
		switch (action) {
			case HARVEST -> level.destroyBlock(target, true, entity);
			case TILL -> {
				ensureHoe(entity);
				level.setBlockAndUpdate(target, Blocks.FARMLAND.defaultBlockState());
				ItemStack hoe = entity.getMainHandItem();
				if (hoe.getItem() instanceof HoeItem && hoe.isDamageableItem())
					hoe.hurtAndBreak(1, entity, EquipmentSlot.MAINHAND);
			}
			// the bucket is a tool, not a consumable - it never empties
			case WATER -> level.setBlockAndUpdate(target, Blocks.WATER.defaultBlockState());
			case PLANT -> {
				ItemStack seed = findPlantableSeed(level, entity, target);
				if (seed == null) return;
				level.setBlockAndUpdate(target, cropState(seed));
				seed.shrink(1);
			}
			case BONEMEAL -> {
				ItemStack bonemeal = findBonemeal(entity);
				if (!bonemeal.isEmpty() && BoneMealItem.growCrop(bonemeal, level, target))
					level.levelEvent(1505, target, 0);
			}
		}
	}

	private void tickCollectingDrops(ServerLevel level, FakePlayerEntity entity) {
		if (JobHelpers.inventoryFull(entity)) {
			phase = Phase.RETURNING;
			return;
		}
		ItemEntity item = nearestRegionDrop(level, entity);
		if (item == null) {
			phase = Phase.SCANNING;
			return;
		}
		if (entity.distanceToSqr(item) <= ITEM_REACH_SQR) {
			pickItem(entity, item);
			pathFailCount = 0;
			return;
		}
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
		if (entity.getNavigation().isDone()) {
			boolean ok = entity.getNavigation().moveTo(item.getX(), item.getY(), item.getZ(), 1.0);
			if (!ok && ++pathFailCount >= MAX_PATH_FAIL) waitForBlocker(level, entity, "farmer: cannot reach dropped items");
		}
	}

	private void tickReturning(ServerLevel level, FakePlayerEntity entity) {
		BlockPos chest = entity.getAIState().depositChest();
		if (chest == null) {
			waitForBlocker(level, entity, "farmer: no deposit container set");
			return;
		}
		if (moveToChest(level, entity)) return;
		if (HopperBlockEntity.getContainerAt(level, chest) == null) {
			waitForBlocker(level, entity, "farmer: deposit container gone");
			return;
		}
		if (!serviceAtChest(level, entity)) return; // still pausing with the chest open
		pathFailCount = 0;
		phase = Phase.SCANNING;
	}

	private void tickWaitingAtChest(ServerLevel level, FakePlayerEntity entity) {
		if (moveToChest(level, entity)) return;
		serviceAtChest(level, entity);
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING);
		if (level.getGameTime() < waitUntilTick) return;
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
		pathFailCount = 0;
		phase = Phase.SCANNING;
	}

	// --- crop / soil predicates ---

	private boolean isTillable(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.DIRT_PATH);
	}

	/** A stack is a seed if it places a crop block (vanilla or modded) - no hardcoded list. */
	private BlockState cropState(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) return null;
		BlockState state = item.getBlock().defaultBlockState();
		return state.getBlock() instanceof CropBlock ? state : null;
	}

	private boolean isSeed(ItemStack stack) {
		return !stack.isEmpty() && cropState(stack) != null;
	}

	private boolean plantableAt(ServerLevel level, FakePlayerEntity entity, BlockPos cropPos) {
		return findPlantableSeed(level, entity, cropPos) != null;
	}

	private ItemStack findPlantableSeed(ServerLevel level, FakePlayerEntity entity, BlockPos cropPos) {
		if (!level.getBlockState(cropPos).isAir()) return null;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			BlockState state = cropState(stack);
			if (state != null && state.canSurvive(level, cropPos)) return stack;
		}
		return null;
	}

	private ItemStack findSeed(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isSeed(inv.getItem(i))) return inv.getItem(i);
		}
		return null;
	}

	/**
	 * Source coordinates along one axis so every cell in [lo, hi] is within {@link #WATER_RADIUS} of a source.
	 * Sources sit every (2*radius+1) blocks starting radius-in from the low edge, with one clamped to the high edge.
	 */
	private Set<Integer> axisAnchors(int lo, int hi) {
		Set<Integer> anchors = new LinkedHashSet<>();
		int step = WATER_RADIUS * 2 + 1;
		for (int a = lo + WATER_RADIUS; a - WATER_RADIUS <= hi; a += step) {
			int clamped = Math.min(a, hi);
			anchors.add(clamped);
			if (clamped == hi) break;
		}
		if (anchors.isEmpty()) anchors.add(Math.min(lo + WATER_RADIUS, hi));
		return anchors;
	}

	// --- inventory / tools ---

	private boolean isUsableHoe(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof HoeItem)) return false;
		if (!stack.isDamageableItem()) return true;
		return stack.getMaxDamage() - stack.getDamageValue() > DURABILITY_RESERVE;
	}

	private boolean hasUsableHoe(FakePlayerEntity entity) {
		if (isUsableHoe(entity.getMainHandItem())) return true;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isUsableHoe(inv.getItem(i))) return true;
		return false;
	}

	private void ensureHoe(FakePlayerEntity entity) {
		if (isUsableHoe(entity.getMainHandItem())) return;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!isUsableHoe(inv.getItem(i))) continue;
			ItemStack hoe = inv.removeItemNoUpdate(i);
			ItemStack prev = entity.getMainHandItem();
			entity.setItemSlot(EquipmentSlot.MAINHAND, hoe);
			if (!prev.isEmpty()) {
				ItemStack leftover = inv.addItem(prev);
				if (!leftover.isEmpty()) entity.spawnAtLocation((ServerLevel) entity.level(), leftover);
			}
			return;
		}
	}

	private boolean hasWaterBucket(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).getItem() == Items.WATER_BUCKET) return true;
		return false;
	}

	private ItemStack findBonemeal(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).getItem() == Items.BONE_MEAL) return inv.getItem(i);
		}
		return ItemStack.EMPTY;
	}

	private int bonemealCount(FakePlayerEntity entity) {
		int total = 0;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.getItem() == Items.BONE_MEAL) total += stack.getCount();
		}
		return total;
	}

	// --- chest service: dump surplus, restock the keep-set ---

	/** Returns false while still pausing with the chest open (caller should not advance the phase yet). */
	private boolean serviceAtChest(ServerLevel level, FakePlayerEntity entity) {
		BlockPos chest = entity.getAIState().depositChest();
		if (chest == null) { JobHelpers.closeContainer(level, entity); return true; }
		Container c = HopperBlockEntity.getContainerAt(level, chest);
		if (c == null) { JobHelpers.closeContainer(level, entity); return true; }
		if (!JobHelpers.pollContainer(level, entity, chest)) return false; // open + pause ~1s before servicing
		dumpInto(c, entity);
		pickHoe(c, entity);
		pickItem(c, entity, Items.WATER_BUCKET, 1);
		pickItem(c, entity, Items.BONE_MEAL, BONEMEAL_RESERVE);
		pickSeeds(c, entity);
		return true;
	}

	/** Deposit produce and seeds beyond the replant reserve; keep tools, water and bonemeal. */
	private void dumpInto(Container chest, FakePlayerEntity entity) {
		Map<net.minecraft.world.item.Item, Integer> kept = new HashMap<>();
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (isToolKeep(stack)) continue;
			if (isSeed(stack)) {
				int already = kept.getOrDefault(stack.getItem(), 0);
				int room = Math.max(0, SEED_RESERVE - already);
				int keepNow = Math.min(stack.getCount(), room);
				kept.put(stack.getItem(), already + keepNow);
				int surplus = stack.getCount() - keepNow;
				if (surplus <= 0) continue;
				ItemStack toDeposit = stack.copyWithCount(surplus);
				ItemStack remaining = HopperBlockEntity.addItem(null, chest, toDeposit, null);
				stack.setCount(keepNow + remaining.getCount());
				if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
				continue;
			}
			ItemStack remaining = HopperBlockEntity.addItem(null, chest, stack, null);
			inv.setItem(i, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
		}
		chest.setChanged();
	}

	private boolean isToolKeep(ItemStack stack) {
		return isUsableHoe(stack)
				|| stack.getItem() == Items.WATER_BUCKET
				|| stack.getItem() == Items.BUCKET
				|| stack.getItem() == Items.BONE_MEAL;
	}

	private void pickHoe(Container chest, FakePlayerEntity entity) {
		if (hasUsableHoe(entity)) return;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (!isUsableHoe(chest.getItem(i))) continue;
			ItemStack hoe = chest.removeItemNoUpdate(i);
			ItemStack leftover = entity.getInventory().addItem(hoe);
			if (!leftover.isEmpty()) chest.setItem(i, leftover);
			chest.setChanged();
			return;
		}
	}

	private void pickSeeds(Container chest, FakePlayerEntity entity) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack stack = chest.getItem(i);
			if (!isSeed(stack)) continue;
			int have = countItem(entity, stack.getItem());
			if (have >= SEED_RESERVE) continue;
			ItemStack taken = stack.split(Math.min(stack.getCount(), SEED_RESERVE - have));
			ItemStack leftover = entity.getInventory().addItem(taken);
			if (!leftover.isEmpty()) stack.grow(leftover.getCount());
			if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
			chest.setChanged();
		}
	}

	private void pickItem(Container chest, FakePlayerEntity entity, net.minecraft.world.item.Item item, int reserve) {
		if (countItem(entity, item) >= reserve) return;
		for (int i = 0; i < chest.getContainerSize() && countItem(entity, item) < reserve; i++) {
			ItemStack stack = chest.getItem(i);
			if (stack.getItem() != item) continue;
			int wanted = reserve - countItem(entity, item);
			ItemStack taken = stack.split(Math.min(stack.getCount(), wanted));
			ItemStack leftover = entity.getInventory().addItem(taken);
			if (!leftover.isEmpty()) stack.grow(leftover.getCount());
			if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
		}
		chest.setChanged();
	}

	private int countItem(FakePlayerEntity entity, net.minecraft.world.item.Item item) {
		int total = 0;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.getItem() == item) total += stack.getCount();
		}
		return total;
	}

	// --- shared movement / waiting (mirrors LumberjackJobExecutor) ---

	private void pickItem(FakePlayerEntity entity, ItemEntity item) {
		if (!item.isAlive()) return;
		ItemStack stack = item.getItem().copy();
		int before = stack.getCount();
		ItemStack remainder = entity.getInventory().addItem(stack);
		int taken = before - remainder.getCount();
		if (taken <= 0) return;
		entity.take(item, taken);
		if (remainder.isEmpty()) item.discard();
		else item.setItem(remainder);
	}

	private ItemEntity nearestRegionDrop(ServerLevel level, FakePlayerEntity entity) {
		AABB box = JobHelpers.regionBox(entity.getAIState());
		if (box == null) return null;
		return level.getEntitiesOfClass(ItemEntity.class, box.inflate(0, 1, 0), e -> e.isAlive() && !e.hasPickUpDelay()).stream()
				.min(Comparator.comparingDouble(entity::distanceToSqr))
				.orElse(null);
	}

	private boolean atChest(FakePlayerEntity entity) {
		BlockPos chest = entity.getAIState().depositChest();
		if (chest == null) return false;
		double dx = entity.getX() - (chest.getX() + 0.5), dz = entity.getZ() - (chest.getZ() + 0.5);
		return dx * dx + dz * dz <= 9.0;
	}

	private boolean moveToChest(ServerLevel level, FakePlayerEntity entity) {
		BlockPos chest = entity.getAIState().depositChest();
		if (chest == null) return false;
		double cx = chest.getX() + 0.5, cz = chest.getZ() + 0.5;
		double dx = entity.getX() - cx, dz = entity.getZ() - cz;
		if (dx * dx + dz * dz <= 9.0) return false;
		JobHelpers.closeContainer(level, entity); // still walking to the chest
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
		if (entity.getNavigation().isDone()) {
			boolean ok = entity.getNavigation().moveTo(cx, chest.getY(), cz, 1.0);
			if (!ok && ++pathFailCount >= MAX_PATH_FAIL) waitForBlocker(level, entity, "farmer: cannot reach deposit container");
		}
		return true;
	}

	private void waitAtChest(ServerLevel level, FakePlayerEntity entity, int ticks) {
		phase = Phase.WAITING_AT_CHEST;
		waitUntilTick = level.getGameTime() + ticks;
		entity.getNavigation().stop();
	}

	private void waitForBlocker(ServerLevel level, FakePlayerEntity entity, String message) {
		pathFailCount = 0;
		waitUntilTick = level.getGameTime() + RETRY_WAIT_TICKS;
		entity.getNavigation().stop();
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING);
		phase = Phase.WAITING_AT_CHEST;
		if (!message.equals(lastBlocker)) { // tell the owner once per distinct problem, not every retry
			entity.sendChat(message + " - waiting 15s before retry");
			lastBlocker = message;
		}
	}

	private BlockPos findStandNear(ServerLevel level, BlockPos target) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos feet = target.relative(dir);
			if (canStandAt(level, feet)) return feet;
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos feet = target.relative(dir).above();
			if (canStandAt(level, feet)) return feet;
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos feet = target.relative(dir).below();
			if (canStandAt(level, feet)) return feet;
		}
		return null;
	}

	private boolean canStandAt(ServerLevel level, BlockPos feet) {
		return JobHelpers.canStandAt(level, feet);
	}

	private boolean near(FakePlayerEntity entity, BlockPos pos, double range) {
		double dx = entity.getX() - (pos.getX() + 0.5);
		double dy = entity.getY() - pos.getY();
		double dz = entity.getZ() - (pos.getZ() + 0.5);
		return dx * dx + dy * dy + dz * dz <= range * range;
	}

	private void lookAndSwing(FakePlayerEntity entity, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		entity.getLookControl().setLookAt(center.x, center.y, center.z);
		entity.swing(InteractionHand.MAIN_HAND);
	}

	private void clearTarget() {
		target = null;
		actionStand = null;
		action = null;
		pathFailCount = 0;
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
		if (entity.level() instanceof ServerLevel sl) JobHelpers.closeContainer(sl, entity);
	}

	@Override
	public void onResume(FakePlayerEntity entity) {
		phase = Phase.SCANNING;
		clearTarget();
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
	}

	@Override
	public CompoundTag serialize() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Phase", phase.ordinal());
		tag.putInt("PathFail", pathFailCount);
		tag.putLong("WaitUntil", waitUntilTick);
		if (action != null) tag.putInt("Action", action.ordinal());
		if (target != null) tag.putLong("Target", target.asLong());
		return tag;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) return;
		Phase[] all = Phase.values();
		int p = tag.contains("Phase") ? tag.getInt("Phase") : 0;
		phase = (p >= 0 && p < all.length) ? all[p] : Phase.SCANNING;
		pathFailCount = tag.contains("PathFail") ? tag.getInt("PathFail") : 0;
		waitUntilTick = tag.contains("WaitUntil") ? tag.getLong("WaitUntil") : 0L;
		target = null;
		actionStand = null;
		action = null;
		// resume cleanly: re-scan rather than trust a stale target/action
		if (phase == Phase.WORKING || phase == Phase.COLLECTING_DROPS) phase = Phase.SCANNING;
	}
}
