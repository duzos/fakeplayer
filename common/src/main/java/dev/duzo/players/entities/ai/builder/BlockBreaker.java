package dev.duzo.players.entities.ai.builder;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;

import java.util.List;
import java.util.function.Predicate;

/** Breaks one block at a time with Miner's progress and swing timing, and puts the drops in the fake's inventory. */
public final class BlockBreaker {
	public enum Result {
		/** Still digging. */
		PROGRESS,
		BROKEN,
		/** Unbreakable, or already gone. */
		BLOCKED,
		/** The drops do not fit in the inventory, so the block was left standing. */
		FULL
	}

	private @Nullable BlockPos target;
	private float progress;
	private int stage = -1;

	/** One tick of work on `pos`. Moving to a different block restarts the progress. */
	public Result tick(ServerLevel level, FakePlayerEntity entity, BlockPos pos) {
		return tick(level, entity, pos, p -> true);
	}

	/** As above, but {@code allowed} is asked once at the moment the block would actually break, and a no ends the dig as BLOCKED. */
	public Result tick(ServerLevel level, FakePlayerEntity entity, BlockPos pos, Predicate<BlockPos> allowed) {
		if (target != null && !target.equals(pos)) reset(level, entity);
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
			reset(level, entity);
			return Result.BLOCKED;
		}
		target = pos.immutable();
		entity.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		entity.swing(InteractionHand.MAIN_HAND);
		progress += delta(level, entity, state, pos);
		updateStage(level, entity, pos);
		if (progress < 1.0F) return Result.PROGRESS;

		if (!allowed.test(pos)) {
			reset(level, entity);
			return Result.BLOCKED;
		}
		ItemStack tool = entity.getMainHandItem();
		boolean broke = breakIntoInventory(level, entity, pos, state, tool);
		if (broke && tool.isDamageableItem()) tool.hurtAndBreak(1, entity, EquipmentSlot.MAINHAND);
		reset(level, entity);
		return broke ? Result.BROKEN : Result.FULL;
	}

	/** Drops any half-dug crack overlay and forgets the target. */
	public void reset(ServerLevel level, FakePlayerEntity entity) {
		if (target != null && stage >= 0) level.destroyBlockProgress(entity.getId(), target, -1);
		target = null;
		progress = 0.0F;
		stage = -1;
	}

	private void updateStage(ServerLevel level, FakePlayerEntity entity, BlockPos pos) {
		int s = Math.min(9, (int) (progress * 10.0F));
		if (s == stage) return;
		stage = s;
		level.destroyBlockProgress(entity.getId(), pos, s);
	}

	private static float delta(ServerLevel level, FakePlayerEntity entity, BlockState state, BlockPos pos) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F) return 0.0F;
		ItemStack tool = entity.getMainHandItem();
		float speed = Math.max(1.0F, tool.getDestroySpeed(state));
		boolean harvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		return speed / hardness / (harvest ? 30.0F : 100.0F);
	}

	/** Whether every drop fits at once, on a copy, so a block with several drops is not broken for only the first. */
	private static boolean fitsTogether(SimpleContainer inv, List<ItemStack> drops) {
		SimpleContainer trial = new SimpleContainer(inv.getContainerSize());
		for (int i = 0; i < inv.getContainerSize(); i++) trial.setItem(i, inv.getItem(i).copy());
		for (ItemStack drop : drops) {
			if (!trial.addItem(drop.copy()).isEmpty()) return false;
		}
		return true;
	}

	private static boolean breakIntoInventory(ServerLevel level, FakePlayerEntity entity, BlockPos pos, BlockState state, ItemStack tool) {
		BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		List<ItemStack> drops = Block.getDrops(state, level, pos, blockEntity, entity, tool);
		if (!fitsTogether(entity.getInventory(), drops)) return false;
		if (!level.destroyBlock(pos, false, entity)) return false;
		for (ItemStack drop : drops) {
			ItemStack left = entity.getInventory().addItem(drop.copy());
			if (!left.isEmpty()) {
				level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, entity.getX(), entity.getY(), entity.getZ(), left));
			}
		}
		return true;
	}
}
