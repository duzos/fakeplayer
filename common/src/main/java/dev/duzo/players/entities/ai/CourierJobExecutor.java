package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

public class CourierJobExecutor implements JobExecutor {
	private static final double ARRIVAL_DIST_SQR = 6.0;
	private static final double SPEED = 1.0;
	private static final int TRANSFER_PER_TICK = 1;
	private static final int IDLE_REPATH_COOLDOWN = 40;

	private enum Phase { TO_SOURCE, PULL, TO_DEPOSIT, DUMP }

	private Phase phase = Phase.TO_SOURCE;
	private int repathCooldown;

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		AIState state = entity.getAIState();
		BlockPos source = state.sourceChest();
		BlockPos deposit = state.depositChest();
		if (source == null || deposit == null) return;

		if (phase != Phase.PULL && phase != Phase.DUMP) JobHelpers.closeContainer(level, entity);

		switch (phase) {
			case TO_SOURCE -> walkTo(entity, source, Phase.PULL);
			case PULL -> {
				Container src = HopperBlockEntity.getContainerAt(level, source);
				if (src == null) {
					JobHelpers.closeContainer(level, entity);
					phase = Phase.TO_DEPOSIT;
					return;
				}
				if (entity.blockPosition().distSqr(source) > ARRIVAL_DIST_SQR) {
					JobHelpers.closeContainer(level, entity);
					phase = Phase.TO_SOURCE;
					return;
				}
				if (!JobHelpers.pollContainer(level, entity, source)) return; // open + pause ~1s before pulling
				SimpleContainer dest = entity.getInventory();
				if (isFull(dest)) {
					phase = Phase.TO_DEPOSIT;
					return;
				}
				int moved = pullMatching(src, dest, state.filter(), TRANSFER_PER_TICK);
				if (moved == 0) phase = Phase.TO_DEPOSIT;
			}
			case TO_DEPOSIT -> walkTo(entity, deposit, Phase.DUMP);
			case DUMP -> {
				Container dst = HopperBlockEntity.getContainerAt(level, deposit);
				if (dst == null) {
					JobHelpers.closeContainer(level, entity);
					phase = Phase.TO_SOURCE;
					return;
				}
				if (entity.blockPosition().distSqr(deposit) > ARRIVAL_DIST_SQR) {
					JobHelpers.closeContainer(level, entity);
					phase = Phase.TO_DEPOSIT;
					return;
				}
				if (!JobHelpers.pollContainer(level, entity, deposit)) return; // open + pause ~1s before depositing
				SimpleContainer src = entity.getInventory();
				int moved = dumpAll(src, dst, TRANSFER_PER_TICK);
				if (moved == 0) phase = Phase.TO_SOURCE;
			}
		}
	}

	private void walkTo(FakePlayerEntity entity, BlockPos target, Phase onArrive) {
		if (entity.blockPosition().distSqr(target) <= ARRIVAL_DIST_SQR) {
			entity.getNavigation().stop();
			phase = onArrive;
			repathCooldown = 0;
			return;
		}
		if (--repathCooldown > 0) return;
		if (entity.getNavigation().isDone()) {
			entity.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, SPEED);
			repathCooldown = IDLE_REPATH_COOLDOWN;
		}
	}

	private boolean isFull(Container c) {
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty()) return false;
			if (s.getCount() < s.getMaxStackSize() && s.getCount() < c.getMaxStackSize()) return false;
		}
		return true;
	}

	private int pullMatching(Container src, Container dst, CompoundTag filter, int budget) {
		int moved = 0;
		for (int i = 0; i < src.getContainerSize() && moved < budget; i++) {
			ItemStack stack = src.getItem(i);
			if (stack.isEmpty()) continue;
			if (!matchesFilter(stack, filter)) continue;
			ItemStack take = stack.copy();
			ItemStack remainder = HopperBlockEntity.addItem(src, dst, take, null);
			int taken = stack.getCount() - remainder.getCount();
			if (taken > 0) {
				stack.shrink(taken);
				if (stack.isEmpty()) src.setItem(i, ItemStack.EMPTY);
				src.setChanged();
				dst.setChanged();
				moved++;
			}
		}
		return moved;
	}

	private int dumpAll(Container src, Container dst, int budget) {
		int moved = 0;
		for (int i = 0; i < src.getContainerSize() && moved < budget; i++) {
			ItemStack stack = src.getItem(i);
			if (stack.isEmpty()) continue;
			ItemStack take = stack.copy();
			ItemStack remainder = HopperBlockEntity.addItem(src, dst, take, null);
			int put = stack.getCount() - remainder.getCount();
			if (put > 0) {
				stack.shrink(put);
				if (stack.isEmpty()) src.setItem(i, ItemStack.EMPTY);
				src.setChanged();
				dst.setChanged();
				moved++;
			}
		}
		return moved;
	}

	private boolean matchesFilter(ItemStack stack, CompoundTag filter) {
		if (filter == null || filter.isEmpty()) return true;
		String tagId = filter.getString("Tag");
		if (!tagId.isEmpty()) {
			ResourceLocation rl = ResourceLocation.tryParse(tagId);
			if (rl == null) return true;
			TagKey<Item> key = TagKey.create(BuiltInRegistries.ITEM.key(), rl);
			return stack.is(key);
		}
		String itemId = filter.getString("Item");
		if (!itemId.isEmpty()) {
			ResourceLocation rl = ResourceLocation.tryParse(itemId);
			if (rl == null) return true;
			Item item = BuiltInRegistries.ITEM.get(rl).map(ref -> ref.value()).orElse(null);
			if (item == null) return true;
			return stack.getItem() == item;
		}
		return true;
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
		if (entity.level() instanceof ServerLevel sl) JobHelpers.closeContainer(sl, entity);
	}

	@Override public void onResume(FakePlayerEntity entity) {}

	@Override
	public CompoundTag serialize() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Phase", phase.name());
		return tag;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) return;
		String name = tag.getString("Phase");
		if (name.isEmpty()) return;
		try { phase = Phase.valueOf(name); } catch (IllegalArgumentException ignored) {}
	}
}
