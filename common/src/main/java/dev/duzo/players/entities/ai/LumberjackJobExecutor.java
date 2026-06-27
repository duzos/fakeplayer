package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LumberjackJobExecutor implements JobExecutor {
	public enum Phase { SCANNING, COLLECTING_DROPS, PATHING_TO_BLOCK, BREAKING, PLANTING, BONEMEALING, RETURNING, WAITING_AT_CHEST }

	private static final int SCAN_BUDGET = 8192;
	private static final int TARGETS_CACHE = 32;
	private static final double REACH_SQR = 20.25;
	private static final int SWING_EVERY = 5;
	private static final int ACTION_COOLDOWN_TICKS = 6;
	private static final int FELL_CAP = 64;
	private static final int MAX_PATH_FAIL = 3;
	private static final int RETRY_WAIT_TICKS = 20 * 15;
	private static final int IDLE_WAIT_TICKS = 20 * 30;
	private static final int SAPLING_RESERVE = 32;
	private static final int BONEMEAL_RESERVE = 32;
	private static final int DURABILITY_RESERVE = 8;
	private static final double VACUUM_RADIUS = 4.0;
	private static final double ITEM_REACH_SQR = 4.0;
	private static final double ACTION_REACH_SQR = 9.0;

	private Phase phase = Phase.SCANNING;
	private BlockPos target;
	private BlockPos actionStand;
	private int breakProgress = 0;
	private int breakTotalTicks = 0;
	private int breakStage = -1;
	private int pathFailCount = 0;
	private int actionCooldown = 0;
	private boolean bailed = false;
	private long waitUntilTick = 0L;
	private String waitMessage = "";
	private PlantTask plantTask;
	private final Deque<BlockPos> targets = new ArrayDeque<>();
	private final List<BlockPos> activeTree = new ArrayList<>();

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		if (bailed) return;
		vacuumNearbyItems(level, entity);
		if (actionCooldown > 0) actionCooldown--;

		// work phases never touch a container; only chest phases re-open it inside serviceAtChest
		switch (phase) {
			case COLLECTING_DROPS, PATHING_TO_BLOCK, BREAKING, PLANTING, BONEMEALING -> JobHelpers.closeContainer(level, entity);
			default -> {}
		}

		switch (phase) {
			case SCANNING -> tickScanning(level, entity);
			case COLLECTING_DROPS -> tickCollectingDrops(level, entity);
			case PATHING_TO_BLOCK -> tickPathing(level, entity);
			case BREAKING -> tickBreaking(level, entity);
			case PLANTING -> tickPlanting(level, entity);
			case BONEMEALING -> tickBonemealing(level, entity);
			case RETURNING -> tickReturning(level, entity);
			case WAITING_AT_CHEST -> tickWaitingAtChest(level, entity);
		}
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
		clearBreakStages((ServerLevel) entity.level(), entity);
		if (entity.level() instanceof ServerLevel sl) JobHelpers.closeContainer(sl, entity);
	}

	@Override
	public void onResume(FakePlayerEntity entity) {}

	@Override
	public CompoundTag serialize() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Phase", phase.ordinal());
		tag.putInt("PathFail", pathFailCount);
		tag.putInt("BreakProgress", breakProgress);
		tag.putInt("BreakTotal", breakTotalTicks);
		tag.putBoolean("Bailed", bailed);
		tag.putLong("WaitUntil", waitUntilTick);
		tag.putString("WaitMessage", waitMessage == null ? "" : waitMessage);
		if (target != null) tag.putLong("Target", target.asLong());
		return tag;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) return;
		int p = tag.contains("Phase") ? tag.getInt("Phase") : 0;
		Phase[] all = Phase.values();
		phase = (p >= 0 && p < all.length) ? all[p] : Phase.SCANNING;
		pathFailCount = tag.contains("PathFail") ? tag.getInt("PathFail") : 0;
		breakProgress = tag.contains("BreakProgress") ? tag.getInt("BreakProgress") : 0;
		breakTotalTicks = tag.contains("BreakTotal") ? tag.getInt("BreakTotal") : 0;
		bailed = false;
		waitUntilTick = tag.contains("WaitUntil") ? tag.getLong("WaitUntil") : 0L;
		waitMessage = tag.contains("WaitMessage") ? tag.getString("WaitMessage") : "";
		target = null;
		actionStand = null;
		plantTask = null;
		activeTree.clear();
		breakStage = -1;
		target = tag.contains("Target") ? BlockPos.of(tag.getLong("Target")) : null;
		if ((phase == Phase.PATHING_TO_BLOCK || phase == Phase.BREAKING || phase == Phase.BONEMEALING) && target == null) {
			phase = Phase.SCANNING;
		}
		if (phase == Phase.PLANTING) phase = Phase.SCANNING;
	}

	private void tickScanning(ServerLevel level, FakePlayerEntity entity) {
		AIState s = entity.getAIState();
		BlockPos a = s.regionA();
		BlockPos b = s.regionB();
		if (a == null || b == null) {
			waitForBlocker(level, entity, "lumberjack: no region set");
			return;
		}
		if (inventoryFull(entity)) {
			phase = Phase.RETURNING;
			return;
		}
		if (nearestRegionDrop(level, entity) != null) {
			phase = Phase.COLLECTING_DROPS;
			pathFailCount = 0;
			return;
		}

		targets.clear();
		List<BlockPos> matches = findLogs(level, a, b, entity.blockPosition());
		if (!matches.isEmpty()) {
			entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
			int n = Math.min(matches.size(), TARGETS_CACHE);
			for (int i = 0; i < n; i++) targets.add(matches.get(i));
			phase = Phase.PATHING_TO_BLOCK;
			pathFailCount = 0;
			return;
		}

		if (!atChest(entity)) {
			phase = Phase.RETURNING;
			return;
		}
		serviceAtChest(level, entity);

		BlockPos sapling = findSaplingInRegion(level, a, b, entity.blockPosition());
		if (sapling != null && hasBonemeal(entity)) {
			target = sapling;
			actionStand = null;
			phase = Phase.BONEMEALING;
			pathFailCount = 0;
			return;
		}

		plantTask = findPlantTask(level, entity, a, b);
		if (plantTask != null) {
			actionStand = null;
			phase = Phase.PLANTING;
			pathFailCount = 0;
			return;
		}

		waitAtChest(level, entity, IDLE_WAIT_TICKS);
	}

	private List<BlockPos> findLogs(ServerLevel level, BlockPos a, BlockPos b, BlockPos origin) {
		List<BlockPos> matches = new ArrayList<>();
		int budget = SCAN_BUDGET;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		outer:
		for (int x = x0; x <= x1; x++)
		for (int y = y0; y <= y1; y++)
		for (int z = z0; z <= z1; z++) {
			if (--budget <= 0) break outer;
			cursor.set(x, y, z);
			if (level.getBlockState(cursor).is(BlockTags.LOGS)) matches.add(cursor.immutable());
		}
		matches.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
		return matches;
	}

	private void tickCollectingDrops(ServerLevel level, FakePlayerEntity entity) {
		if (inventoryFull(entity)) {
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
		if (entity.getNavigation().isDone()) {
			boolean ok = entity.getNavigation().moveTo(item.getX(), item.getY(), item.getZ(), 1.0);
			if (!ok && ++pathFailCount >= MAX_PATH_FAIL) waitForBlocker(level, entity, "lumberjack: cannot reach dropped items");
		}
	}

	private void tickPathing(ServerLevel level, FakePlayerEntity entity) {
		if (target == null) target = targets.peek();
		if (target == null) { phase = Phase.SCANNING; return; }
		if (!level.getBlockState(target).is(BlockTags.LOGS)) {
			targets.poll();
			target = null;
			phase = targets.isEmpty() ? Phase.SCANNING : Phase.PATHING_TO_BLOCK;
			return;
		}
		double tx = target.getX() + 0.5, ty = target.getY() + 0.5, tz = target.getZ() + 0.5;
		if (entity.getEyePosition().distanceToSqr(tx, ty, tz) <= REACH_SQR) {
			entity.getNavigation().stop();
			entity.getLookControl().setLookAt(tx, ty, tz);
			beginBreaking(level, entity);
			return;
		}
		if (actionStand == null || !canStandAt(level, actionStand)) actionStand = findStandNear(level, target);
		if (actionStand == null) {
			waitForBlocker(level, entity, "lumberjack: no stand position for log");
			return;
		}
		if (near(entity, actionStand, 1.2)) {
			entity.getNavigation().stop();
			beginBreaking(level, entity);
			return;
		}
		if (entity.getNavigation().isDone()) {
			boolean ok = entity.getNavigation().moveTo(actionStand.getX() + 0.5, actionStand.getY(), actionStand.getZ() + 0.5, 1.0);
			if (!ok && ++pathFailCount >= MAX_PATH_FAIL) waitForBlocker(level, entity, "lumberjack: pathing failed");
		}
	}

	private void beginBreaking(ServerLevel level, FakePlayerEntity entity) {
		activeTree.clear();
		activeTree.addAll(collectTree(level, target));
		if (activeTree.isEmpty()) {
			clearTarget();
			phase = Phase.SCANNING;
			return;
		}
		if (needsReturnBeforeFelling(entity, activeTree.size()) || !hasUsableAxe(entity)) {
			phase = Phase.RETURNING;
			return;
		}
		breakProgress = 0;
		breakStage = -1;
		breakTotalTicks = totalBreakTicks(level, entity, activeTree);
		phase = Phase.BREAKING;
		pathFailCount = 0;
	}

	private void tickBreaking(ServerLevel level, FakePlayerEntity entity) {
		if (target == null) { finishBreaking(level, entity, false); phase = Phase.SCANNING; return; }
		if (activeTree.isEmpty()) activeTree.addAll(collectTree(level, target));
		activeTree.removeIf(pos -> !level.getBlockState(pos).is(BlockTags.LOGS));
		if (activeTree.isEmpty()) {
			finishBreaking(level, entity, false);
			phase = Phase.SCANNING;
			return;
		}
		double tx = target.getX() + 0.5, ty = target.getY() + 0.5, tz = target.getZ() + 0.5;
		entity.getLookControl().setLookAt(tx, ty, tz);
		if (entity.getEyePosition().distanceToSqr(tx, ty, tz) > REACH_SQR) {
			clearBreakStages(level, entity);
			phase = Phase.PATHING_TO_BLOCK;
			return;
		}
		ensureAxe(entity);
		if (!hasUsableAxe(entity)) {
			finishBreaking(level, entity, false);
			phase = Phase.RETURNING;
			return;
		}
		if (breakTotalTicks <= 0) breakTotalTicks = totalBreakTicks(level, entity, activeTree);
		if (breakProgress % SWING_EVERY == 0) entity.swing(InteractionHand.MAIN_HAND);
		breakProgress++;
		updateBreakStages(level, entity);
		if (breakProgress < breakTotalTicks) return;
		finishBreaking(level, entity, true);
		phase = Phase.SCANNING;
	}

	private void finishBreaking(ServerLevel level, FakePlayerEntity entity, boolean destroy) {
		clearBreakStages(level, entity);
		if (destroy) {
			ItemStack tool = entity.getMainHandItem();
			for (BlockPos pos : new ArrayList<>(activeTree)) {
				boolean broke = level.destroyBlock(pos, true, entity);
				if (broke && isLoggingTool(tool)) tool.hurtAndBreak(1, entity, EquipmentSlot.MAINHAND);
			}
		}
		targets.poll();
		clearTarget();
		activeTree.clear();
		breakProgress = 0;
		breakTotalTicks = 0;
	}

	private void clearTarget() {
		target = null;
		actionStand = null;
		pathFailCount = 0;
	}

	private void tickPlanting(ServerLevel level, FakePlayerEntity entity) {
		if (plantTask == null || plantTask.remaining.isEmpty()) {
			plantTask = null;
			phase = Phase.SCANNING;
			return;
		}
		BlockPos pos = plantTask.remaining.peek();
		if (!canPlant(level, pos, plantTask.state)) {
			plantTask.remaining.poll();
			return;
		}
		if (actionStand == null || !canStandAt(level, actionStand)) actionStand = findStandNear(level, pos);
		if (actionStand == null) {
			waitForBlocker(level, entity, "lumberjack: cannot reach sapling spot");
			return;
		}
		if (!near(entity, actionStand, 1.2)) {
			entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
			if (entity.getNavigation().isDone()) {
				boolean ok = entity.getNavigation().moveTo(actionStand.getX() + 0.5, actionStand.getY(), actionStand.getZ() + 0.5, 1.0);
				if (!ok && ++pathFailCount >= MAX_PATH_FAIL) waitForBlocker(level, entity, "lumberjack: cannot reach sapling spot");
			}
			return;
		}
		if (actionCooldown > 0) return;
		ItemStack sapling = findMatchingSapling(entity, plantTask.state);
		if (sapling.isEmpty()) {
			plantTask = null;
			phase = Phase.SCANNING;
			return;
		}
		lookAndSwing(entity, pos);
		level.setBlockAndUpdate(pos, plantTask.state);
		sapling.shrink(1);
		plantTask.remaining.poll();
		actionCooldown = ACTION_COOLDOWN_TICKS;
		pathFailCount = 0;
	}

	private void tickBonemealing(ServerLevel level, FakePlayerEntity entity) {
		if (target == null || !(level.getBlockState(target).getBlock() instanceof SaplingBlock)) {
			target = null;
			phase = Phase.SCANNING;
			return;
		}
		ItemStack bonemeal = findBonemeal(entity);
		if (bonemeal.isEmpty()) {
			phase = Phase.SCANNING;
			return;
		}
		if (actionStand == null || !canStandAt(level, actionStand)) actionStand = findStandNear(level, target);
		if (actionStand == null) {
			waitForBlocker(level, entity, "lumberjack: cannot reach sapling to bonemeal");
			return;
		}
		if (!near(entity, actionStand, 1.2)) {
			entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
			if (entity.getNavigation().isDone()) {
				boolean ok = entity.getNavigation().moveTo(actionStand.getX() + 0.5, actionStand.getY(), actionStand.getZ() + 0.5, 1.0);
				if (!ok && ++pathFailCount >= MAX_PATH_FAIL) waitForBlocker(level, entity, "lumberjack: cannot reach sapling to bonemeal");
			}
			return;
		}
		if (actionCooldown > 0) return;
		lookAndSwing(entity, target);
		if (BoneMealItem.growCrop(bonemeal, level, target)) {
			level.levelEvent(1505, target, 0);
		}
		actionCooldown = ACTION_COOLDOWN_TICKS;
		pathFailCount = 0;
		phase = Phase.SCANNING;
	}

	private void tickReturning(ServerLevel level, FakePlayerEntity entity) {
		BlockPos chest = entity.getAIState().depositChest();
		if (chest == null) {
			waitForBlocker(level, entity, "lumberjack: no deposit container set");
			return;
		}
		if (moveToChest(level, entity)) return;
		Container c = HopperBlockEntity.getContainerAt(level, chest);
		if (c == null) {
			waitForBlocker(level, entity, "lumberjack: deposit container gone");
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

	private Set<BlockPos> collectTree(ServerLevel level, BlockPos start) {
		Set<BlockPos> result = new LinkedHashSet<>();
		if (!level.getBlockState(start).is(BlockTags.LOGS)) return result;
		result.add(start);
		Deque<BlockPos> bfs = new ArrayDeque<>();
		bfs.add(start);
		int[][] offsets = {
			{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
			{0, 1, 0}, {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1}, {1, 1, 1}, {1, 1, -1}, {-1, 1, 1}, {-1, 1, -1}
		};
		while (!bfs.isEmpty() && result.size() < FELL_CAP) {
			BlockPos cur = bfs.poll();
			for (int[] off : offsets) {
				if (result.size() >= FELL_CAP) break;
				BlockPos n = cur.offset(off[0], off[1], off[2]);
				if (n.getY() < start.getY() || result.contains(n)) continue;
				if (level.getBlockState(n).is(BlockTags.LOGS)) {
					result.add(n);
					bfs.offer(n);
				}
			}
		}
		return result;
	}

	private void vacuumNearbyItems(ServerLevel level, FakePlayerEntity entity) {
		AABB box = entity.getBoundingBox().inflate(VACUUM_RADIUS);
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && !e.hasPickUpDelay());
		for (ItemEntity item : items) pickItem(entity, item);
	}

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
		AABB box = regionBox(entity.getAIState());
		if (box == null) return null;
		return level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && !e.hasPickUpDelay()).stream()
			.min(Comparator.comparingDouble(entity::distanceToSqr))
			.orElse(null);
	}

	private AABB regionBox(AIState s) {
		BlockPos a = s.regionA();
		BlockPos b = s.regionB();
		if (a == null || b == null) return null;
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		return new AABB(x0, y0, z0, x1 + 1.0, y1 + 1.0, z1 + 1.0);
	}

	private void ensureAxe(FakePlayerEntity entity) {
		ItemStack main = entity.getMainHandItem();
		if (isUsableAxe(main)) return;
		SimpleContainer inv = entity.getInventory();
		int bestIdx = -1;
		float bestSpeed = -1f;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!isUsableAxe(stack)) continue;
			float speed = axeSpeed(stack);
			if (speed > bestSpeed) { bestSpeed = speed; bestIdx = i; }
		}
		if (bestIdx < 0) return;
		ItemStack axe = inv.removeItemNoUpdate(bestIdx);
		if (!main.isEmpty()) {
			ItemStack leftover = inv.addItem(main);
			if (!leftover.isEmpty()) spawnAtEntity(entity, leftover);
		}
		entity.setItemSlot(EquipmentSlot.MAINHAND, axe);
	}

	private void spawnAtEntity(FakePlayerEntity entity, ItemStack stack) {
		ItemEntity drop = new ItemEntity(entity.level(), entity.getX(), entity.getY(), entity.getZ(), stack);
		entity.level().addFreshEntity(drop);
	}

	private boolean isLoggingTool(ItemStack stack) {
		return !stack.isEmpty() && axeSpeed(stack) > 1.5f;
	}

	private boolean isUsableAxe(ItemStack stack) {
		if (!isLoggingTool(stack)) return false;
		if (!stack.isDamageableItem()) return true;
		return stack.getMaxDamage() - stack.getDamageValue() > DURABILITY_RESERVE;
	}

	private boolean hasUsableAxe(FakePlayerEntity entity) {
		if (isUsableAxe(entity.getMainHandItem())) return true;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isUsableAxe(inv.getItem(i))) return true;
		}
		return false;
	}

	private float axeSpeed(ItemStack stack) {
		return stack.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState());
	}

	private boolean needsReturnBeforeFelling(FakePlayerEntity entity, int logs) {
		ItemStack main = entity.getMainHandItem();
		if (!isLoggingTool(main) || !main.isDamageableItem()) return false;
		return main.getMaxDamage() - main.getDamageValue() <= logs + DURABILITY_RESERVE;
	}

	private boolean inventoryFull(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).isEmpty()) return false;
		}
		return true;
	}

	/** Returns false while still pausing with the chest open (caller should not advance the phase yet). */
	private boolean serviceAtChest(ServerLevel level, FakePlayerEntity entity) {
		BlockPos chest = entity.getAIState().depositChest();
		if (chest == null) { JobHelpers.closeContainer(level, entity); return true; }
		Container c = HopperBlockEntity.getContainerAt(level, chest);
		if (c == null) { JobHelpers.closeContainer(level, entity); return true; }
		if (!JobHelpers.pollContainer(level, entity, chest)) return false; // open + pause ~1s before servicing
		dumpInto(c, entity);
		pickBetterAxe(c, entity);
		pickSaplings(c, entity);
		pickBonemeal(c, entity);
		return true;
	}

	private void dumpInto(Container chest, FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || isKeep(stack)) continue;
			ItemStack remaining = HopperBlockEntity.addItem(null, chest, stack, null);
			inv.setItem(i, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
		}
		chest.setChanged();
	}

	private boolean isKeep(ItemStack stack) {
		if (isLoggingTool(stack)) return true;
		if (isSapling(stack)) return true;
		if (isBonemeal(stack)) return true;
		return stack.has(DataComponents.FOOD);
	}

	private void pickBetterAxe(Container chest, FakePlayerEntity entity) {
		ItemStack current = entity.getMainHandItem();
		float curSpeed = isUsableAxe(current) ? axeSpeed(current) : -1f;
		int bestIdx = -1;
		float bestSpeed = curSpeed;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack stack = chest.getItem(i);
			if (!isUsableAxe(stack)) continue;
			float speed = axeSpeed(stack);
			if (speed > bestSpeed) { bestSpeed = speed; bestIdx = i; }
		}
		if (bestIdx < 0) return;
		ItemStack axe = chest.removeItemNoUpdate(bestIdx);
		if (!current.isEmpty()) chest.setItem(bestIdx, current);
		entity.setItemSlot(EquipmentSlot.MAINHAND, axe);
		chest.setChanged();
	}

	private void pickSaplings(Container chest, FakePlayerEntity entity) {
		if (saplingCount(entity) >= SAPLING_RESERVE) return;
		for (int i = 0; i < chest.getContainerSize() && saplingCount(entity) < SAPLING_RESERVE; i++) {
			ItemStack stack = chest.getItem(i);
			if (!isSapling(stack)) continue;
			int wanted = SAPLING_RESERVE - saplingCount(entity);
			ItemStack taken = stack.split(Math.min(stack.getCount(), wanted));
			ItemStack leftover = entity.getInventory().addItem(taken);
			if (!leftover.isEmpty()) stack.grow(leftover.getCount());
			if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
		}
		chest.setChanged();
	}

	private void pickBonemeal(Container chest, FakePlayerEntity entity) {
		if (bonemealCount(entity) >= BONEMEAL_RESERVE) return;
		for (int i = 0; i < chest.getContainerSize() && bonemealCount(entity) < BONEMEAL_RESERVE; i++) {
			ItemStack stack = chest.getItem(i);
			if (!isBonemeal(stack)) continue;
			int wanted = BONEMEAL_RESERVE - bonemealCount(entity);
			ItemStack taken = stack.split(Math.min(stack.getCount(), wanted));
			ItemStack leftover = entity.getInventory().addItem(taken);
			if (!leftover.isEmpty()) stack.grow(leftover.getCount());
			if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
		}
		chest.setChanged();
	}

	private PlantTask findPlantTask(ServerLevel level, FakePlayerEntity entity, BlockPos a, BlockPos b) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			BlockState state = saplingState(stack);
			if (state == null) continue;
			if (requiresTwoByTwo(state)) {
				if (stack.getCount() < 4) continue;
				List<BlockPos> square = findTwoByTwoPlantSpot(level, a, b, state);
				if (square != null) return new PlantTask(state, new ArrayDeque<>(square));
			} else {
				BlockPos pos = findSinglePlantSpot(level, a, b, state);
				if (pos != null) return new PlantTask(state, new ArrayDeque<>(List.of(pos)));
			}
		}
		return null;
	}

	private BlockPos findSinglePlantSpot(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		for (int y = y0; y <= y1; y++)
		for (int x = x0; x <= x1; x++)
		for (int z = z0; z <= z1; z++) {
			BlockPos pos = new BlockPos(x, y, z);
			if (canPlant(level, pos, state)) return pos;
		}
		return null;
	}

	private List<BlockPos> findTwoByTwoPlantSpot(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		for (int y = y0; y <= y1; y++)
		for (int x = x0; x < x1; x++)
		for (int z = z0; z < z1; z++) {
			List<BlockPos> positions = List.of(new BlockPos(x, y, z), new BlockPos(x + 1, y, z), new BlockPos(x, y, z + 1), new BlockPos(x + 1, y, z + 1));
			boolean ok = true;
			for (BlockPos pos : positions) {
				if (!canPlant(level, pos, state)) { ok = false; break; }
			}
			if (ok) return positions;
		}
		return null;
	}

	private boolean canPlant(ServerLevel level, BlockPos pos, BlockState state) {
		return level.getBlockState(pos).isAir() && state.canSurvive(level, pos);
	}

	private BlockPos findSaplingInRegion(ServerLevel level, BlockPos a, BlockPos b, BlockPos origin) {
		List<BlockPos> matches = new ArrayList<>();
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = x0; x <= x1; x++)
		for (int y = y0; y <= y1; y++)
		for (int z = z0; z <= z1; z++) {
			cursor.set(x, y, z);
			if (level.getBlockState(cursor).getBlock() instanceof SaplingBlock) matches.add(cursor.immutable());
		}
		return matches.stream().min(Comparator.comparingDouble(p -> p.distSqr(origin))).orElse(null);
	}

	private ItemStack findMatchingSapling(FakePlayerEntity entity, BlockState state) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			BlockState candidate = saplingState(stack);
			if (candidate != null && candidate.is(state.getBlock())) return stack;
		}
		return ItemStack.EMPTY;
	}

	private int matchingSaplingCount(FakePlayerEntity entity, BlockState state) {
		int total = 0;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			BlockState candidate = saplingState(stack);
			if (candidate != null && candidate.is(state.getBlock())) total += stack.getCount();
		}
		return total;
	}

	private ItemStack findBonemeal(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (isBonemeal(stack)) return stack;
		}
		return ItemStack.EMPTY;
	}

	private int saplingCount(FakePlayerEntity entity) {
		int total = 0;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (isSapling(stack)) total += stack.getCount();
		}
		return total;
	}

	private int bonemealCount(FakePlayerEntity entity) {
		int total = 0;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (isBonemeal(stack)) total += stack.getCount();
		}
		return total;
	}

	private boolean hasBonemeal(FakePlayerEntity entity) {
		return !findBonemeal(entity).isEmpty();
	}

	private boolean isSapling(ItemStack stack) {
		return !stack.isEmpty() && saplingState(stack) != null;
	}

	private boolean isBonemeal(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() == Items.BONE_MEAL;
	}

	private BlockState saplingState(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
		BlockState state = blockItem.getBlock().defaultBlockState();
		return state.getBlock() instanceof SaplingBlock ? state : null;
	}

	private boolean requiresTwoByTwo(BlockState state) {
		return state.is(Blocks.SPRUCE_SAPLING) || state.is(Blocks.JUNGLE_SAPLING);
	}

	private void updateBreakStages(ServerLevel level, FakePlayerEntity entity) {
		int stage = Math.min(9, (int) ((breakProgress / (float) Math.max(1, breakTotalTicks)) * 10.0F));
		if (stage == breakStage) return;
		breakStage = stage;
		for (int i = 0; i < activeTree.size(); i++) {
			level.destroyBlockProgress(breakId(entity, i), activeTree.get(i), stage);
		}
	}

	private void clearBreakStages(ServerLevel level, FakePlayerEntity entity) {
		if (breakStage < 0) return;
		for (int i = 0; i < activeTree.size(); i++) {
			level.destroyBlockProgress(breakId(entity, i), activeTree.get(i), -1);
		}
		breakStage = -1;
	}

	private int breakId(FakePlayerEntity entity, int index) {
		return entity.getId() * 1000 + index + 1;
	}

	private int totalBreakTicks(ServerLevel level, FakePlayerEntity entity, List<BlockPos> positions) {
		int total = 0;
		for (BlockPos pos : positions) {
			BlockState state = level.getBlockState(pos);
			if (!state.is(BlockTags.LOGS)) continue;
			total += ticksToBreak(level, entity, state, pos);
		}
		return Math.max(1, total);
	}

	private int ticksToBreak(ServerLevel level, FakePlayerEntity entity, BlockState state, BlockPos pos) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F) return 1;
		ItemStack tool = entity.getMainHandItem();
		float speed = Math.max(1.0F, tool.getDestroySpeed(state));
		float delta = speed / hardness / (canHarvest(entity, state) ? 30.0F : 100.0F);
		if (delta <= 0.0F) return 1;
		return Math.max(1, (int) Math.ceil(1.0F / delta));
	}

	private boolean canHarvest(FakePlayerEntity entity, BlockState state) {
		return !state.requiresCorrectToolForDrops() || entity.getMainHandItem().isCorrectToolForDrops(state);
	}

	private boolean atChest(FakePlayerEntity entity) {
		BlockPos chest = entity.getAIState().depositChest();
		if (chest == null) return false;
		double cx = chest.getX() + 0.5, cz = chest.getZ() + 0.5;
		double dx = entity.getX() - cx, dz = entity.getZ() - cz;
		return dx * dx + dz * dz <= 9.0;
	}

	private void waitAtChest(ServerLevel level, FakePlayerEntity entity, int ticks) {
		phase = Phase.WAITING_AT_CHEST;
		waitUntilTick = level.getGameTime() + ticks;
		waitMessage = "";
		entity.getNavigation().stop();
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
			if (!ok && ++pathFailCount >= MAX_PATH_FAIL) waitForBlocker(level, entity, "lumberjack: cannot reach deposit container");
		}
		return true;
	}

	private void waitForBlocker(ServerLevel level, FakePlayerEntity entity, String message) {
		bailed = false;
		pathFailCount = 0;
		waitUntilTick = level.getGameTime() + RETRY_WAIT_TICKS;
		waitMessage = message;
		entity.getNavigation().stop();
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING);
		phase = Phase.WAITING_AT_CHEST;
	}

	private BlockPos findStandNear(ServerLevel level, BlockPos target) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos feet = target.relative(dir);
			if (canStandAt(level, feet)) return feet;
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos feet = target.relative(dir).below();
			if (canStandAt(level, feet)) return feet;
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos feet = target.relative(dir).above();
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

	private static class PlantTask {
		final BlockState state;
		final Deque<BlockPos> remaining;

		PlantTask(BlockState state, Deque<BlockPos> remaining) {
			this.state = state;
			this.remaining = remaining;
		}
	}
}