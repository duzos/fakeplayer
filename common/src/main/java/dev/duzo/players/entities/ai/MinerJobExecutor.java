package dev.duzo.players.entities.ai;

import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

public class MinerJobExecutor implements JobExecutor {
	public enum Phase { SCANNING, PATHING_TO_BLOCK, MINING, CHECK_INVENTORY, RETURNING }

	private static final String DEFAULT_FILTER = "c:ores";
	private static final int SCAN_BUDGET = 8192;
	private static final int TARGETS_CACHE = 32;
	private static final double REACH_SQR = 20.25;
	private static final int MAX_PATH_FAIL = 3;
	private static final int DURABILITY_RESERVE = 8;

	private Phase phase = Phase.SCANNING;
	private int pathFailCount = 0;
	private long throttleUntilTick = 0L;
	private boolean bailed = false;
	private final Deque<BlockPos> targets = new ArrayDeque<>();

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		if (bailed) return;
		PlayersConfig cfg = PlayersConfig.get();

		if (entity.getBlockY() < cfg.minerBailY) {
			bail(entity, "miner: bailing, below Y=" + cfg.minerBailY);
			return;
		}

		switch (phase) {
			case SCANNING -> tickScanning(level, entity);
			case PATHING_TO_BLOCK -> tickPathing(level, entity, cfg);
			case MINING -> tickMining(level, entity, cfg);
			case CHECK_INVENTORY -> tickCheckInventory(entity);
			case RETURNING -> tickReturning(level, entity);
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
		tag.putInt("PathFail", pathFailCount);
		tag.putBoolean("Bailed", bailed);
		return tag;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) return;
		int p = tag.contains("Phase") ? tag.getInt("Phase") : 0;
		Phase[] all = Phase.values();
		phase = (p >= 0 && p < all.length) ? all[p] : Phase.SCANNING;
		pathFailCount = tag.contains("PathFail") ? tag.getInt("PathFail") : 0;
		bailed = tag.contains("Bailed") && tag.getBoolean("Bailed");
	}

	private void tickScanning(ServerLevel level, FakePlayerEntity entity) {
		AIState s = entity.getAIState();
		BlockPos a = s.regionA();
		BlockPos b = s.regionB();
		if (a == null || b == null) {
			bail(entity, "miner: no region set");
			return;
		}
		TagKey<Block> tag = filterTag(s);
		targets.clear();
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
			BlockState state = level.getBlockState(cursor);
			if (state.isAir()) continue;
			if (!state.is(tag)) continue;
			matches.add(cursor.immutable());
		}
		if (matches.isEmpty()) {
			bail(entity, "miner: region empty");
			return;
		}
		BlockPos pos = entity.blockPosition();
		matches.sort(Comparator.comparingDouble(p -> p.distSqr(pos)));
		int n = Math.min(matches.size(), TARGETS_CACHE);
		for (int i = 0; i < n; i++) targets.add(matches.get(i));
		phase = Phase.PATHING_TO_BLOCK;
		pathFailCount = 0;
	}

	private void tickPathing(ServerLevel level, FakePlayerEntity entity, PlayersConfig cfg) {
		BlockPos t = targets.peek();
		if (t == null) { phase = Phase.SCANNING; return; }
		if (cfg.minerNeverMineBlockUnderFeet) {
			BlockPos feet = entity.blockPosition();
			if (t.equals(feet) || t.equals(feet.below())) {
				targets.poll();
				return;
			}
		}
		double tx = t.getX() + 0.5, ty = t.getY() + 0.5, tz = t.getZ() + 0.5;
		if (entity.getEyePosition().distanceToSqr(tx, ty, tz) <= REACH_SQR) {
			entity.getNavigation().stop();
			entity.getLookControl().setLookAt(tx, ty, tz);
			phase = Phase.MINING;
			throttleUntilTick = level.getGameTime();
			return;
		}
		if (entity.getNavigation().isDone()) {
			boolean ok = entity.getNavigation().moveTo(tx, t.getY(), tz, 1.0);
			if (!ok) {
				pathFailCount++;
				targets.poll();
				if (pathFailCount >= MAX_PATH_FAIL) {
					bail(entity, "miner: pathing failed");
					return;
				}
				if (targets.isEmpty()) phase = Phase.SCANNING;
			}
		}
	}

	private void tickMining(ServerLevel level, FakePlayerEntity entity, PlayersConfig cfg) {
		BlockPos t = targets.peek();
		if (t == null) { phase = Phase.SCANNING; return; }
		BlockState state = level.getBlockState(t);
		if (state.isAir()) {
			targets.poll();
			phase = targets.isEmpty() ? Phase.SCANNING : Phase.PATHING_TO_BLOCK;
			return;
		}
		double tx = t.getX() + 0.5, ty = t.getY() + 0.5, tz = t.getZ() + 0.5;
		entity.getLookControl().setLookAt(tx, ty, tz);
		if (entity.getEyePosition().distanceToSqr(tx, ty, tz) > REACH_SQR) {
			phase = Phase.PATHING_TO_BLOCK;
			return;
		}
		long now = level.getGameTime();
		if (now < throttleUntilTick) return;

		if (cfg.minerLavaCobbleSafety && hasLavaNeighbor(level, t)) {
			BlockPos lava = lavaNeighbor(level, t);
			if (lava != null) {
				if (!consumeCobbleAndPlace(level, entity, lava)) {
					phase = Phase.RETURNING;
					return;
				}
			}
		}

		ensurePickaxe(entity);
		entity.swing(InteractionHand.MAIN_HAND);
		ItemStack tool = entity.getMainHandItem();
		boolean broke = level.destroyBlock(t, true, entity);
		if (broke && isMiningTool(tool)) {
			tool.hurtAndBreak(1, entity, EquipmentSlot.MAINHAND);
		}
		targets.poll();
		double bps = Math.max(0.5, cfg.minerMaxBlocksPerSecond);
		throttleUntilTick = now + Math.max(1L, (long) (20.0 / bps));
		phase = Phase.CHECK_INVENTORY;
	}

	private void tickCheckInventory(FakePlayerEntity entity) {
		if (inventoryFull(entity) || pickaxeNearBroken(entity)) {
			phase = Phase.RETURNING;
			return;
		}
		phase = targets.isEmpty() ? Phase.SCANNING : Phase.PATHING_TO_BLOCK;
	}

	private void tickReturning(ServerLevel level, FakePlayerEntity entity) {
		AIState s = entity.getAIState();
		BlockPos chest = s.depositChest();
		if (chest == null) {
			bail(entity, "miner: no deposit chest set");
			return;
		}
		double cx = chest.getX() + 0.5, cz = chest.getZ() + 0.5;
		double dx = entity.getX() - cx, dz = entity.getZ() - cz;
		if (dx * dx + dz * dz > 9.0) {
			if (entity.getNavigation().isDone()) {
				boolean ok = entity.getNavigation().moveTo(cx, chest.getY(), cz, 1.0);
				if (!ok) {
					pathFailCount++;
					if (pathFailCount >= MAX_PATH_FAIL) {
						bail(entity, "miner: cannot reach deposit chest");
					}
				}
			}
			return;
		}
		Container c = HopperBlockEntity.getContainerAt(level, chest);
		if (c == null) {
			bail(entity, "miner: deposit chest gone");
			return;
		}
		dumpInto(c, entity);
		pickBetterPickaxe(c, entity);
		pathFailCount = 0;
		phase = Phase.SCANNING;
	}

	private TagKey<Block> filterTag(AIState s) {
		String name = s.filter().contains("Tag") ? s.filter().getString("Tag") : DEFAULT_FILTER;
		if (name.isEmpty()) name = DEFAULT_FILTER;
		if (name.startsWith("#")) name = name.substring(1);
		ResourceLocation rl = ResourceLocation.tryParse(name);
		if (rl == null) rl = ResourceLocation.parse(DEFAULT_FILTER);
		return TagKey.create(Registries.BLOCK, rl);
	}

	private boolean hasLavaNeighbor(ServerLevel level, BlockPos pos) {
		return lavaNeighbor(level, pos) != null;
	}

	private BlockPos lavaNeighbor(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (level.getFluidState(n).getType() == Fluids.LAVA) return n;
			if (level.getFluidState(n).getType() == Fluids.FLOWING_LAVA) return n;
		}
		return null;
	}

	private boolean consumeCobbleAndPlace(ServerLevel level, FakePlayerEntity entity, BlockPos lavaPos) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (stack.getItem() == Items.COBBLESTONE) {
				stack.shrink(1);
				if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
				level.setBlock(lavaPos, Blocks.COBBLESTONE.defaultBlockState(), 3);
				return true;
			}
		}
		return false;
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
		return stack.has(DataComponents.TOOL) || pickaxeSpeed(stack) > 1.5f;
	}

	private boolean isUsablePickaxe(ItemStack stack) {
		if (!isMiningTool(stack)) return false;
		if (pickaxeSpeed(stack) <= 1.5f) return false;
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

	private boolean inventoryFull(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).isEmpty()) return false;
		}
		return true;
	}

	private void dumpInto(Container chest, FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (isKeep(stack)) continue;
			ItemStack remaining = HopperBlockEntity.addItem(null, chest, stack, null);
			if (remaining.isEmpty()) {
				inv.setItem(i, ItemStack.EMPTY);
			} else {
				inv.setItem(i, remaining);
			}
		}
		chest.setChanged();
	}

	private boolean isKeep(ItemStack stack) {
		if (isMiningTool(stack)) return true;
		if (stack.getItem() == Items.COBBLESTONE) return true;
		if (stack.has(DataComponents.FOOD)) return true;
		return false;
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
		if (!current.isEmpty()) {
			chest.setItem(bestIdx, current);
		}
		entity.setItemSlot(EquipmentSlot.MAINHAND, pick);
		chest.setChanged();
	}

	private void bail(FakePlayerEntity entity, String message) {
		bailed = true;
		entity.getNavigation().stop();
		entity.sendChat(message);
	}
}
