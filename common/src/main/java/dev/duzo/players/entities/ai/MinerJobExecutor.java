package dev.duzo.players.entities.ai;

import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

public class MinerJobExecutor implements JobExecutor {
	public enum Phase { INIT, QUARRY, RETURNING, CAP, DONE }

	private static final int MAX_PATH_FAIL = 3;
	private static final int RETRY_WAIT_TICKS = 20 * 15;
	private static final int DURABILITY_RESERVE = 8;
	private static final int BUILD_RESERVE = 64;
	private static final int LIQUID_SCAN_RADIUS = 6;
	private static final String DEFAULT_FILTER = "c:ores";

	private Phase phase = Phase.INIT;
	private Phase returnPhase = Phase.QUARRY;
	private boolean bailed;
	private int pathFailCount;
	private long throttleUntilTick;

	private int minX, maxX, minZ, maxZ;
	private int topY, bottomY, currentY;
	private int cursor;
	private int capCursor;
	private BlockPos activeTarget;
	private BlockPos activeStand;
	private float miningProgress;
	private int miningStage = -1;
	private boolean waiting;
	private long waitUntilTick;
	private String waitMessage = "";

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		if (bailed) return;
		PlayersConfig cfg = PlayersConfig.get();
		if (waiting) {
			if (level.getGameTime() < waitUntilTick) return;
			clearWait(entity);
		}

		switch (phase) {
			case INIT -> init(level, entity, cfg);
			case QUARRY -> tickQuarry(level, entity, cfg);
			case RETURNING -> tickReturning(level, entity);
			case CAP -> tickCap(level, entity, cfg);
			case DONE -> {}
		}
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
	}

	@Override
	public void onResume(FakePlayerEntity entity) {}

	@Override
	public CompoundTag serialize() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Phase", phase.ordinal());
		tag.putInt("ReturnPhase", returnPhase.ordinal());
		tag.putBoolean("Bailed", bailed);
		tag.putInt("PathFail", pathFailCount);
		tag.putLong("Throttle", throttleUntilTick);
		tag.putInt("MinX", minX);
		tag.putInt("MaxX", maxX);
		tag.putInt("MinZ", minZ);
		tag.putInt("MaxZ", maxZ);
		tag.putInt("TopY", topY);
		tag.putInt("BottomY", bottomY);
		tag.putInt("CurrentY", currentY);
		tag.putInt("Cursor", resumeCursor());
		tag.putInt("CapCursor", capCursor);
		tag.putBoolean("Waiting", waiting);
		tag.putLong("WaitUntil", waitUntilTick);
		tag.putString("WaitMessage", waitMessage == null ? "" : waitMessage);
		return tag;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) return;
		phase = phase(tag.contains("Phase") ? tag.getInt("Phase") : Phase.INIT.ordinal());
		returnPhase = phase(tag.contains("ReturnPhase") ? tag.getInt("ReturnPhase") : Phase.QUARRY.ordinal());
		bailed = tag.contains("Bailed") && tag.getBoolean("Bailed");
		pathFailCount = 0;
		throttleUntilTick = tag.contains("Throttle") ? tag.getLong("Throttle") : 0L;
		minX = tag.contains("MinX") ? tag.getInt("MinX") : 0;
		maxX = tag.contains("MaxX") ? tag.getInt("MaxX") : 0;
		minZ = tag.contains("MinZ") ? tag.getInt("MinZ") : 0;
		maxZ = tag.contains("MaxZ") ? tag.getInt("MaxZ") : 0;
		topY = tag.contains("TopY") ? tag.getInt("TopY") : 0;
		bottomY = tag.contains("BottomY") ? tag.getInt("BottomY") : 0;
		currentY = tag.contains("CurrentY") ? tag.getInt("CurrentY") : topY;
		cursor = tag.contains("Cursor") ? tag.getInt("Cursor") : 0;
		capCursor = tag.contains("CapCursor") ? tag.getInt("CapCursor") : 0;
		waiting = tag.contains("Waiting") && tag.getBoolean("Waiting");
		waitUntilTick = tag.contains("WaitUntil") ? tag.getLong("WaitUntil") : 0L;
		waitMessage = tag.contains("WaitMessage") ? tag.getString("WaitMessage") : "";
		activeTarget = null;
		activeStand = null;
		resetMining();
	}

	private int resumeCursor() {
		if (phase != Phase.QUARRY || activeTarget == null) return cursor;
		return Math.max(0, cursor - 1);
	}

	private static Phase phase(int ordinal) {
		Phase[] all = Phase.values();
		return ordinal >= 0 && ordinal < all.length ? all[ordinal] : Phase.INIT;
	}

	private void init(ServerLevel level, FakePlayerEntity entity, PlayersConfig cfg) {
		AIState s = entity.getAIState();
		BlockPos a = s.regionA();
		BlockPos b = s.regionB();
		if (a == null || b == null) {
			waitForBlocker(level, entity, "miner: mark a quarry region first");
			return;
		}
		minX = Math.min(a.getX(), b.getX());
		maxX = Math.max(a.getX(), b.getX());
		minZ = Math.min(a.getZ(), b.getZ());
		maxZ = Math.max(a.getZ(), b.getZ());
		topY = Math.max(a.getY(), b.getY());
		int markedBottom = Math.min(a.getY(), b.getY());
		bottomY = markedBottom < topY - 1 ? markedBottom : cfg.minerBailY;
		currentY = topY;
		cursor = 0;
		capCursor = 0;
		activeTarget = null;
		activeStand = null;
		pathFailCount = 0;
		phase = Phase.QUARRY;
		entity.sendChat("miner: quarry started " + width() + "x" + length() + " to Y=" + bottomY);
		walkToSafeStep(level, entity);
	}

	private void tickQuarry(ServerLevel level, FakePlayerEntity entity, PlayersConfig cfg) {
		if (currentY < bottomY) {
			phase = Phase.CAP;
			capCursor = 0;
			entity.sendChat("miner: quarry floor reached, capping top");
			return;
		}
		if (needsService(entity)) {
			returnForService(entity, Phase.QUARRY);
			return;
		}
		if (level.getGameTime() < throttleUntilTick) return;

		if (!ensureStairStep(level, entity)) {
			return;
		}

		if (plugNearestLiquidHazard(level, entity)) {
			clearActive();
			double bps = Math.max(0.5, cfg.minerMaxBlocksPerSecond);
			throttleUntilTick = level.getGameTime() + Math.max(1L, (long) (20.0 / bps));
			return;
		}

		if (activeTarget == null || activeStand == null || level.getBlockState(activeTarget).isAir()) {
			selectNextTarget(level, entity);
		}
		if (activeTarget == null || activeStand == null) {
			return;
		}

		if (!near(entity, activeStand, 1.8)) {
			walkTo(entity, activeStand);
			return;
		}
		entity.getNavigation().stop();
		BlockState state = level.getBlockState(activeTarget);
		if (state.isAir()) {
			clearActive();
			return;
		}
		ensurePickaxe(entity);
		if (!isUsablePickaxe(entity.getMainHandItem())) {
			returnForService(entity, Phase.QUARRY);
			return;
		}
		if (!canHarvest(entity, state)) {
			waitForBlocker(level, entity, "miner: no correct tool for " + blockName(state));
			return;
		}
		if (isLiquid(level, activeTarget)) {
			if (!placeBuildBlock(level, entity, activeTarget)) {
				waitForBlocker(level, entity, "miner: out of build blocks for liquid hazard");
				return;
			}
			clearActive();
			return;
		}
		entity.getLookControl().setLookAt(activeTarget.getX() + 0.5, activeTarget.getY() + 0.5, activeTarget.getZ() + 0.5);
		entity.swing(InteractionHand.MAIN_HAND);
		miningProgress += miningDelta(level, entity, state, activeTarget);
		updateMiningStage(level, entity, activeTarget);
		if (miningProgress < 1.0F) return;

		ItemStack tool = entity.getMainHandItem();
		boolean broke = breakIntoInventory(level, entity, activeTarget, state, tool);
		if (broke && isMiningTool(tool)) tool.hurtAndBreak(1, entity, EquipmentSlot.MAINHAND);
		clearActive(level, entity);

		double bps = Math.max(0.5, cfg.minerMaxBlocksPerSecond);
		throttleUntilTick = level.getGameTime() + Math.max(1L, (long) (20.0 / bps));
	}

	private void selectNextTarget(ServerLevel level, FakePlayerEntity entity) {
		int area = area();
		while (cursor < area) {
			int targetIndex = cursor;
			BlockPos target = posForIndex(targetIndex, currentY);
			if (isReservedStairFloor(target)) {
				cursor++;
				continue;
			}
			if (isProtected(level, target)) {
				cursor++;
				continue;
			}
			BlockState state = level.getBlockState(target);
			if (state.isAir()) {
				cursor++;
				continue;
			}
			if (!canHarvest(entity, state)) {
				waitForBlocker(level, entity, "miner: no correct tool for " + blockName(state));
				return;
			}
			BlockPos stand = findStand(level, target, targetIndex);
			if (stand == null) {
				stand = mineFromCurrentPosition(entity);
			}
			cursor++;
			activeTarget = target;
			activeStand = stand;
			resetMining();
			return;
		}
		currentY--;
		cursor = 0;
		activeTarget = null;
		activeStand = null;
		if (currentY >= bottomY) walkToSafeStep(level, entity);
	}

	private BlockPos findStand(ServerLevel level, BlockPos target, int targetIndex) {
		BlockPos best = targetIndex > 0 ? feetForClearedCell(level, posForIndex(targetIndex - 1, currentY), target) : null;
		if (best != null) return best;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			best = feetForClearedCell(level, target.relative(direction), target);
			if (best != null) return best;
		}
		BlockPos step = stairStepForCurrentLayer();
		if (best == null && step.distSqr(target) <= 20.25 && canStandAt(level, step.above(), target)) {
			best = step.above();
		}
		return best;
	}

	private BlockPos feetForClearedCell(ServerLevel level, BlockPos cell, BlockPos target) {
		if (!insideFootprint(cell)) return null;
		if (!isAdjacent(cell, target)) return null;
		BlockPos feet = cell;
		if (canStandAt(level, feet, target)) return feet;
		feet = cell.above();
		return canStandAt(level, feet, target) ? feet : null;
	}

	private boolean canStandAt(ServerLevel level, BlockPos feet, BlockPos target) {
		if (feet.equals(target) || feet.below().equals(target)) return false;
		if (!level.getBlockState(feet).isAir()) return false;
		if (!level.getBlockState(feet.above()).isAir()) return false;
		return !level.getBlockState(feet.below()).isAir();
	}

	private boolean isAdjacent(BlockPos a, BlockPos b) {
		return a.getY() == b.getY() && Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) == 1;
	}

	private boolean insideFootprint(BlockPos pos) {
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	private boolean near(FakePlayerEntity entity, BlockPos pos, double range) {
		double dx = entity.getX() - (pos.getX() + 0.5);
		double dy = entity.getY() - pos.getY();
		double dz = entity.getZ() - (pos.getZ() + 0.5);
		return dx * dx + dy * dy + dz * dz <= range * range;
	}

	private void clearActive() {
		activeTarget = null;
		activeStand = null;
		pathFailCount = 0;
		resetMining();
	}

	private void clearActive(ServerLevel level, FakePlayerEntity entity) {
		clearMiningStage(level, entity);
		clearActive();
	}

	private void resetMining() {
		miningProgress = 0.0F;
		miningStage = -1;
	}

	private void updateMiningStage(ServerLevel level, FakePlayerEntity entity, BlockPos pos) {
		int stage = Math.min(9, (int) (miningProgress * 10.0F));
		if (stage == miningStage) return;
		miningStage = stage;
		level.destroyBlockProgress(entity.getId(), pos, stage);
	}

	private void clearMiningStage(ServerLevel level, FakePlayerEntity entity) {
		if (activeTarget != null && miningStage >= 0) {
			level.destroyBlockProgress(entity.getId(), activeTarget, -1);
		}
	}

	private float miningDelta(ServerLevel level, FakePlayerEntity entity, BlockState state, BlockPos pos) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F) return 0.0F;
		ItemStack tool = entity.getMainHandItem();
		float speed = Math.max(1.0F, tool.getDestroySpeed(state));
		return speed / hardness / (canHarvest(entity, state) ? 30.0F : 100.0F);
	}

	private boolean canHarvest(FakePlayerEntity entity, BlockState state) {
		return !state.requiresCorrectToolForDrops() || entity.getMainHandItem().isCorrectToolForDrops(state);
	}

	private void tickCap(ServerLevel level, FakePlayerEntity entity, PlayersConfig cfg) {
		if (needsService(entity)) {
			returnForService(entity, Phase.CAP);
			return;
		}
		if (level.getGameTime() < throttleUntilTick) return;
		if (capCursor >= area()) {
			phase = Phase.DONE;
			entity.mutateAIState(s -> s.setRunning(false));
			entity.sendChat("miner: quarry complete");
			return;
		}
		BlockPos target = posForIndex(capCursor++, topY + 1);
		walkToTopRim(entity);
		if (isProtected(level, target)) return;
		if (!level.getBlockState(target).isAir() && !isLiquid(level, target)) return;
		if (!placeBuildBlock(level, entity, target)) {
			waitForBlocker(level, entity, "miner: out of build blocks for cap");
			return;
		}
		double bps = Math.max(0.5, cfg.minerMaxBlocksPerSecond);
		throttleUntilTick = level.getGameTime() + Math.max(1L, (long) (20.0 / bps));
	}

	private void returnForService(FakePlayerEntity entity, Phase resume) {
		if (entity.level() instanceof ServerLevel level) {
			clearMiningStage(level, entity);
		}
		returnPhase = resume;
		phase = Phase.RETURNING;
		pathFailCount = 0;
		activeTarget = null;
		activeStand = null;
		resetMining();
		entity.getNavigation().stop();
	}

	private void tickReturning(ServerLevel level, FakePlayerEntity entity) {
		AIState s = entity.getAIState();
		BlockPos chest = s.depositChest();
		if (chest == null) {
			waitForBlocker(level, entity, "miner: no deposit container set");
			return;
		}
		double cx = chest.getX() + 0.5, cz = chest.getZ() + 0.5;
		double dx = entity.getX() - cx, dz = entity.getZ() - cz;
		if (dx * dx + dz * dz > 9.0) {
			if (entity.getNavigation().isDone()) {
				boolean ok = entity.getNavigation().moveTo(cx, chest.getY(), cz, 1.0);
				if (!ok && ++pathFailCount >= MAX_PATH_FAIL) {
					pathFailCount = 0;
					waitForBlocker(level, entity, "miner: cannot reach deposit container");
				}
			}
			return;
		}
		Container c = HopperBlockEntity.getContainerAt(level, chest);
		if (c == null) {
			waitForBlocker(level, entity, "miner: deposit container gone");
			return;
		}
		dumpInto(c, entity);
		takeUsefulSupplies(c, entity);
		pickBetterPickaxe(c, entity);
		if (shouldEat(entity) && !eatFromInventory(entity)) {
			waitForBlocker(level, entity, "miner: hungry and no food");
			return;
		}
		if (!hasUsablePickaxe(entity)) {
			waitForBlocker(level, entity, "miner: no usable pickaxe");
			return;
		}
		phase = returnPhase;
		pathFailCount = 0;
		if (phase == Phase.QUARRY) walkToSafeStep(level, entity);
		else walkToTopRim(entity);
	}

	private boolean ensureStairStep(ServerLevel level, FakePlayerEntity entity) {
		BlockPos step = stairStepForCurrentLayer();
		if (!clearHeadroom(level, entity, step.above())) return false;
		if (isProtected(level, step)) {
			waitForBlocker(level, entity, "miner: protected block on stair path");
			return false;
		}
		BlockState state = level.getBlockState(step);
		if (!state.isAir() && !isLiquid(level, step) && isBuildBlockState(state)) return true;
		if (!state.isAir() && !isLiquid(level, step)) {
			ensurePickaxe(entity);
			ItemStack tool = entity.getMainHandItem();
			if (!isUsablePickaxe(tool)) {
				returnForService(entity, Phase.QUARRY);
				return false;
			}
			if (!canHarvest(entity, state)) {
				waitForBlocker(level, entity, "miner: no correct tool for stair block " + blockName(state));
				return false;
			}
			if (!breakIntoInventory(level, entity, step, state, tool)) {
				waitForBlocker(level, entity, "miner: cannot clear stair path");
				return false;
			}
			if (isMiningTool(tool)) tool.hurtAndBreak(1, entity, EquipmentSlot.MAINHAND);
			state = level.getBlockState(step);
		}
		if (state.isAir() || isLiquid(level, step)) {
			if (placeBuildBlock(level, entity, step)) return true;
			waitForBlocker(level, entity, "miner: out of build blocks for stair path");
			return false;
		}
		return true;
	}

	private boolean plugNearestLiquidHazard(ServerLevel level, FakePlayerEntity entity) {
		BlockPos hazard = findNearestLiquidHazard(level, entity.blockPosition());
		if (hazard == null) return false;
		if (!placeBuildBlock(level, entity, hazard)) {
			waitForBlocker(level, entity, "miner: out of build blocks for liquid hazard");
			return true;
		}
		return true;
	}

	private BlockPos findNearestLiquidHazard(ServerLevel level, BlockPos origin) {
		BlockPos bestSource = null;
		BlockPos bestFlow = null;
		double bestSourceDist = Double.MAX_VALUE;
		double bestFlowDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -LIQUID_SCAN_RADIUS; dx <= LIQUID_SCAN_RADIUS; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -LIQUID_SCAN_RADIUS; dz <= LIQUID_SCAN_RADIUS; dz++) {
					pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!isPotentialHazardPos(pos)) continue;
					if (!isLiquid(level, pos)) continue;
					double dist = pos.distSqr(origin);
					if (level.getFluidState(pos).isSource()) {
						if (dist < bestSourceDist) {
							bestSourceDist = dist;
							bestSource = pos.immutable();
						}
					} else if (dist < bestFlowDist) {
						bestFlowDist = dist;
						bestFlow = pos.immutable();
					}
				}
			}
		}
		return bestSource != null ? bestSource : bestFlow;
	}

	private boolean isPotentialHazardPos(BlockPos pos) {
		if (pos.getY() > topY + 1 || pos.getY() < bottomY) return false;
		return pos.getX() >= minX - 1 && pos.getX() <= maxX + 1
				&& pos.getZ() >= minZ - 1 && pos.getZ() <= maxZ + 1;
	}

	private boolean clearHeadroom(ServerLevel level, FakePlayerEntity entity, BlockPos pos) {
		for (int i = 0; i < 2; i++) {
			BlockPos p = pos.above(i);
			if (level.getBlockState(p).isAir()) continue;
			if (isProtected(level, p)) {
				waitForBlocker(level, entity, "miner: protected block in stair headroom");
				return false;
			}
			BlockState state = level.getBlockState(p);
			ensurePickaxe(entity);
			ItemStack tool = entity.getMainHandItem();
			if (!isUsablePickaxe(tool)) {
				returnForService(entity, Phase.QUARRY);
				return false;
			}
			if (!canHarvest(entity, state)) {
				waitForBlocker(level, entity, "miner: no correct tool for stair headroom " + blockName(state));
				return false;
			}
			if (!breakIntoInventory(level, entity, p, state, tool)) {
				waitForBlocker(level, entity, "miner: cannot clear stair headroom");
				return false;
			}
			if (isMiningTool(tool)) tool.hurtAndBreak(1, entity, EquipmentSlot.MAINHAND);
		}
		return true;
	}

	private boolean breakIntoInventory(ServerLevel level, FakePlayerEntity entity, BlockPos pos, BlockState state, ItemStack tool) {
		if (state.isAir()) return false;
		BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		java.util.List<ItemStack> drops = entity == null
				? java.util.List.of()
				: Block.getDrops(state, level, pos, blockEntity, entity, tool);
		boolean broke = level.destroyBlock(pos, false, entity);
		if (!broke) return false;
		if (entity == null) return true;
		for (ItemStack drop : drops) {
			addFilteredDrop(entity, drop, matchesBlockFilter(entity, state));
		}
		return true;
	}

	private void addFilteredDrop(FakePlayerEntity entity, ItemStack stack, boolean sourceBlockMatchesFilter) {
		if (stack.isEmpty()) return;
		if (isBuildBlock(stack)) {
			int needed = BUILD_RESERVE - buildBlockCount(entity);
			if (needed <= 0) return;
			ItemStack kept = stack.copy();
			kept.setCount(Math.min(stack.getCount(), needed));
			addOrDrop(entity, kept);
			return;
		}
		if (!sourceBlockMatchesFilter && !matchesInventoryFilter(entity, stack)) return;
		addOrDrop(entity, stack);
	}

	private boolean matchesInventoryFilter(FakePlayerEntity entity, ItemStack stack) {
		String raw = entity.getAIState().filter().contains("Tag") ? entity.getAIState().filter().getString("Tag") : DEFAULT_FILTER;
		if (raw == null || raw.isBlank()) raw = DEFAULT_FILTER;
		for (String part : raw.split(",")) {
			String token = part.trim();
			if (token.isEmpty()) continue;
			if (matchesFilterToken(stack, token)) return true;
		}
		return false;
	}

	private boolean matchesFilterToken(ItemStack stack, String token) {
		boolean explicitTag = token.startsWith("#");
		String name = explicitTag ? token.substring(1).trim() : token;
		ResourceLocation id = ResourceLocation.tryParse(name);
		if (id == null) return false;
		if (!explicitTag && BuiltInRegistries.ITEM.getOptional(id).map(stack::is).orElse(false)) {
			return true;
		}
		return stack.is(TagKey.create(Registries.ITEM, id));
	}

	private boolean matchesBlockFilter(FakePlayerEntity entity, BlockState state) {
		String raw = entity.getAIState().filter().contains("Tag") ? entity.getAIState().filter().getString("Tag") : DEFAULT_FILTER;
		if (raw == null || raw.isBlank()) raw = DEFAULT_FILTER;
		for (String part : raw.split(",")) {
			String token = part.trim();
			if (token.isEmpty()) continue;
			if (matchesBlockFilterToken(state, token)) return true;
		}
		return false;
	}

	private boolean matchesBlockFilterToken(BlockState state, String token) {
		boolean explicitTag = token.startsWith("#");
		String name = explicitTag ? token.substring(1).trim() : token;
		ResourceLocation id = ResourceLocation.tryParse(name);
		if (id == null) return false;
		if (!explicitTag && BuiltInRegistries.BLOCK.getOptional(id).map(state::is).orElse(false)) {
			return true;
		}
		return state.is(TagKey.create(Registries.BLOCK, id));
	}

	private void addOrDrop(FakePlayerEntity entity, ItemStack stack) {
		if (stack.isEmpty()) return;
		ItemStack leftover = entity.getInventory().addItem(stack.copy());
		if (leftover.isEmpty()) return;
		ItemEntity drop = new ItemEntity(entity.level(), entity.getX(), entity.getY(), entity.getZ(), leftover);
		entity.level().addFreshEntity(drop);
	}

	private void walkToSafeStep(ServerLevel level, FakePlayerEntity entity) {
		BlockPos step = stairStepForCurrentLayer();
		walkTo(entity, step.above());
	}

	private void walkToTopRim(FakePlayerEntity entity) {
		BlockPos p = posForPerimeterIndex(0, topY + 2);
		walkTo(entity, p);
	}

	private void walkTo(FakePlayerEntity entity, BlockPos pos) {
		if (!entity.getNavigation().isDone()) return;
		boolean ok = entity.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
		if (!ok && ++pathFailCount >= MAX_PATH_FAIL) {
			pathFailCount = 0;
			if (phase == Phase.QUARRY && activeTarget != null) {
				activeStand = mineFromCurrentPosition(entity);
				return;
			}
			waitForBlocker((ServerLevel) entity.level(), entity, "miner: cannot path through quarry");
		}
	}

	private BlockPos mineFromCurrentPosition(FakePlayerEntity entity) {
		entity.getNavigation().stop();
		return entity.blockPosition();
	}

	private boolean isReservedStairFloor(BlockPos pos) {
		if (pos.getY() < bottomY || pos.getY() > topY) return false;
		BlockPos step = stairStepForLayer(pos.getY());
		return pos.getX() == step.getX() && pos.getZ() == step.getZ();
	}

	private BlockPos stairStepForCurrentLayer() {
		return stairStepForLayer(currentY);
	}

	private BlockPos stairStepForLayer(int y) {
		int depth = Math.max(0, topY - y);
		return posForPerimeterIndex(depth, y);
	}

	private BlockPos posForPerimeterIndex(int index, int y) {
		int w = width();
		int l = length();
		int perimeter = Math.max(1, 2 * w + 2 * l - 4);
		int i = Math.floorMod(index, perimeter);
		if (i < w) return new BlockPos(minX + i, y, minZ);
		i -= w;
		if (i < l - 1) return new BlockPos(maxX, y, minZ + 1 + i);
		i -= l - 1;
		if (i < w - 1) return new BlockPos(maxX - 1 - i, y, maxZ);
		i -= w - 1;
		return new BlockPos(minX, y, maxZ - 1 - i);
	}

	private BlockPos posForIndex(int index, int y) {
		int row = index / width();
		int offset = index % width();
		int x = (row & 1) == 0 ? offset : width() - 1 - offset;
		int z = index / width();
		return new BlockPos(minX + x, y, minZ + z);
	}

	private int width() {
		return Math.max(1, maxX - minX + 1);
	}

	private int length() {
		return Math.max(1, maxZ - minZ + 1);
	}

	private int area() {
		return width() * length();
	}

	private boolean needsService(FakePlayerEntity entity) {
		return inventoryFull(entity) || pickaxeNearBroken(entity) || shouldEat(entity) || !hasUsablePickaxe(entity);
	}

	private boolean isProtected(ServerLevel level, BlockPos pos) {
		return level.getBlockEntity(pos) != null;
	}

	private boolean isLiquid(ServerLevel level, BlockPos pos) {
		return level.getFluidState(pos).getType() == Fluids.LAVA
				|| level.getFluidState(pos).getType() == Fluids.FLOWING_LAVA
				|| level.getFluidState(pos).getType() == Fluids.WATER
				|| level.getFluidState(pos).getType() == Fluids.FLOWING_WATER;
	}

	private boolean placeBuildBlock(ServerLevel level, FakePlayerEntity entity, BlockPos pos) {
		if (!consumeBuildBlock(entity)) return false;
		level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
		entity.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	private boolean consumeBuildBlock(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!isBuildBlock(stack)) continue;
			stack.shrink(1);
			if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
			return true;
		}
		return false;
	}

	private int buildBlockCount(FakePlayerEntity entity) {
		int count = 0;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (isBuildBlock(stack)) count += stack.getCount();
		}
		return count;
	}

	private boolean isBuildBlock(ItemStack stack) {
		if (stack.isEmpty()) return false;
		Item item = stack.getItem();
		return item == Items.COBBLESTONE
				|| item == Items.COBBLED_DEEPSLATE
				|| item == Items.DIRT
				|| item == Items.STONE
				|| item == Items.DEEPSLATE;
	}

	private boolean isBuildBlockState(BlockState state) {
		return state.is(Blocks.COBBLESTONE)
				|| state.is(Blocks.COBBLED_DEEPSLATE)
				|| state.is(Blocks.DIRT)
				|| state.is(Blocks.STONE)
				|| state.is(Blocks.DEEPSLATE);
	}

	private String blockName(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private void ensurePickaxe(FakePlayerEntity entity) {
		ItemStack main = entity.getMainHandItem();
		if (isUsablePickaxe(main)) return;
		SimpleContainer inv = entity.getInventory();
		int bestIdx = -1;
		float bestSpeed = -1f;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!isUsablePickaxe(s)) continue;
			float speed = pickaxeSpeed(s);
			if (speed > bestSpeed) { bestSpeed = speed; bestIdx = i; }
		}
		if (bestIdx < 0) return;
		ItemStack pick = inv.removeItemNoUpdate(bestIdx);
		if (!main.isEmpty()) {
			ItemStack leftover = inv.addItem(main);
			if (!leftover.isEmpty()) {
				net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(entity.level(), entity.getX(), entity.getY(), entity.getZ(), leftover);
				entity.level().addFreshEntity(drop);
			}
		}
		entity.setItemSlot(EquipmentSlot.MAINHAND, pick);
	}

	private boolean isMiningTool(ItemStack stack) {
		if (stack.isEmpty()) return false;
		return pickaxeSpeed(stack) > 1.5f;
	}

	private boolean isUsablePickaxe(ItemStack stack) {
		if (!isMiningTool(stack)) return false;
		if (!stack.isDamageableItem()) return true;
		return stack.getMaxDamage() - stack.getDamageValue() > DURABILITY_RESERVE;
	}

	private float pickaxeSpeed(ItemStack stack) {
		return stack.getDestroySpeed(Blocks.STONE.defaultBlockState());
	}

	private boolean pickaxeNearBroken(FakePlayerEntity entity) {
		ItemStack main = entity.getMainHandItem();
		if (!isMiningTool(main)) return false;
		if (!main.isDamageableItem()) return false;
		return main.getMaxDamage() - main.getDamageValue() <= DURABILITY_RESERVE;
	}

	private boolean hasUsablePickaxe(FakePlayerEntity entity) {
		if (isUsablePickaxe(entity.getMainHandItem())) return true;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isUsablePickaxe(inv.getItem(i))) return true;
		}
		return false;
	}

	private boolean shouldEat(FakePlayerEntity entity) {
		return entity.getHealth() < entity.getMaxHealth() && hasFood(entity);
	}

	private boolean hasFood(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isFood(inv.getItem(i))) return true;
		}
		return false;
	}

	private boolean eatFromInventory(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!isFood(stack)) continue;
			entity.swing(InteractionHand.MAIN_HAND);
			stack.shrink(1);
			if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
			entity.heal(4.0F);
			return true;
		}
		return false;
	}

	private boolean inventoryFull(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).isEmpty()) return false;
		}
		return true;
	}

	private void dumpInto(Container chest, FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		int keptBuildBlocks = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (isKeep(stack)) continue;
			if (isBuildBlock(stack)) {
				int keep = Math.max(0, BUILD_RESERVE - keptBuildBlocks);
				int move = Math.max(0, stack.getCount() - keep);
				keptBuildBlocks += stack.getCount() - move;
				if (move <= 0) continue;
				ItemStack moving = stack.copy();
				moving.setCount(move);
				ItemStack remaining = HopperBlockEntity.addItem(null, chest, moving, null);
				int moved = move - remaining.getCount();
				if (moved > 0) stack.shrink(moved);
				if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
				continue;
			}
			ItemStack remaining = HopperBlockEntity.addItem(null, chest, stack, null);
			inv.setItem(i, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
		}
		chest.setChanged();
	}

	private boolean isKeep(ItemStack stack) {
		if (isMiningTool(stack)) return true;
		return isFood(stack);
	}

	private boolean isFood(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.FOOD);
	}

	private void takeUsefulSupplies(Container chest, FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack stack = chest.getItem(i);
			if (stack.isEmpty()) continue;
			if (!shouldTakeFromDeposit(entity, stack)) continue;
			ItemStack moved = stack.copy();
			ItemStack leftover = inv.addItem(moved);
			int taken = moved.getCount() - leftover.getCount();
			if (taken > 0) chest.removeItem(i, taken);
			if (inventoryFull(entity)) break;
		}
		chest.setChanged();
	}

	private boolean shouldTakeFromDeposit(FakePlayerEntity entity, ItemStack stack) {
		if (isUsablePickaxe(stack)) return true;
		if (isFood(stack)) return true;
		if (isBuildBlock(stack)) return false;
		return matchesInventoryFilter(entity, stack);
	}

	private void pickBetterPickaxe(Container chest, FakePlayerEntity entity) {
		ItemStack current = entity.getMainHandItem();
		float curSpeed = isUsablePickaxe(current) ? pickaxeSpeed(current) : -1f;
		int bestIdx = -1;
		float bestSpeed = curSpeed;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack s = chest.getItem(i);
			if (!isUsablePickaxe(s)) continue;
			float speed = pickaxeSpeed(s);
			if (speed > bestSpeed) { bestSpeed = speed; bestIdx = i; }
		}
		if (bestIdx < 0) return;
		ItemStack pick = chest.removeItemNoUpdate(bestIdx);
		if (!current.isEmpty()) chest.setItem(bestIdx, current);
		entity.setItemSlot(EquipmentSlot.MAINHAND, pick);
		chest.setChanged();
	}

	private void waitForBlocker(ServerLevel level, FakePlayerEntity entity, String message) {
		entity.getNavigation().stop();
		entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING);
		if (!waiting || waitMessage == null || !waitMessage.equals(message)) {
			entity.sendChat(message + " - waiting 15s before retry");
		}
		waiting = true;
		waitMessage = message;
		waitUntilTick = level.getGameTime() + RETRY_WAIT_TICKS;
	}

	private void clearWait(FakePlayerEntity entity) {
		waiting = false;
		waitUntilTick = 0L;
		waitMessage = "";
		if (entity.getPhysicalState() == FakePlayerEntity.PhysicalState.SITTING) {
			entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
		}
	}

	private void bail(FakePlayerEntity entity, String message) {
		bailed = true;
		entity.getNavigation().stop();
		entity.sendChat(message);
	}
}
