package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class FishermanJobExecutor implements JobExecutor {
	private enum Phase { TO_SPOT, CAST, WAIT, REEL, TO_DEPOSIT, DUMP }
	private static final int DEPOSIT_EVERY = 16;     // "every X fishes"
	private static final int BASE_WAIT_TICKS = 20 * 12;
	private static final double SPEED = 1.0;

	private Phase phase = Phase.TO_SPOT;
	private int caught = 0;
	private long waitUntil = 0L;

	@Override public void tick(ServerLevel level, FakePlayerEntity entity) {
		AIState s = entity.getAIState();
		BlockPos spot = s.waypoint();
		BlockPos deposit = s.depositChest();
		if (spot == null) return; // no spot set: idle (GUI shows "Waypoint unset")

		switch (phase) {
			case TO_SPOT -> {
				if (JobHelpers.walkTo(entity, spot, SPEED)) { entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING); phase = Phase.CAST; }
			}
			case CAST -> {
				if (rod(entity).isEmpty()) return; // wait for a rod in inventory
				entity.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
				entity.swing(InteractionHand.MAIN_HAND);
				int lure = enchant(entity, Enchantments.LURE);
				waitUntil = level.getGameTime() + Math.max(20, BASE_WAIT_TICKS - lure * 20 * 5L);
				phase = Phase.WAIT;
			}
			case WAIT -> { if (level.getGameTime() >= waitUntil) phase = Phase.REEL; }
			case REEL -> {
				entity.swing(InteractionHand.MAIN_HAND);
				for (ItemStack drop : rollCatch(level, entity, spot)) {
					ItemStack rem = entity.getInventory().addItem(drop);
					if (!rem.isEmpty()) entity.spawnAtLocation(level, rem);
				}
				damageRod(entity);
				caught++;
				boolean rodBroken = rod(entity).isEmpty();
				if ((caught >= DEPOSIT_EVERY || rodBroken) && deposit != null) {
					caught = 0;
					entity.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
					phase = Phase.TO_DEPOSIT;
				} else {
					phase = Phase.CAST;
				}
			}
			case TO_DEPOSIT -> { if (JobHelpers.walkTo(entity, deposit, SPEED)) phase = Phase.DUMP; }
			case DUMP -> {
				Container dst = JobHelpers.containerAt(level, deposit);
				if (dst == null) { phase = Phase.TO_SPOT; return; }
				dumpFish(entity, dst);
				phase = Phase.TO_SPOT;
			}
		}
	}

	private List<ItemStack> rollCatch(ServerLevel level, FakePlayerEntity e, BlockPos spot) {
		LootTable table = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
		int luck = enchant(e, Enchantments.LUCK_OF_THE_SEA);
		LootParams params = new LootParams.Builder(level)
			.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(spot))
			.withParameter(LootContextParams.TOOL, rod(e))
			.withLuck(luck)
			.create(LootContextParamSets.FISHING);
		return table.getRandomItems(params);
	}

	private ItemStack rod(FakePlayerEntity e) {
		ItemStack main = e.getMainHandItem();
		if (main.getItem() == Items.FISHING_ROD) return main;
		SimpleContainer inv = e.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++)
			if (inv.getItem(i).getItem() == Items.FISHING_ROD) return inv.getItem(i);
		return ItemStack.EMPTY;
	}

	private int enchant(FakePlayerEntity e, ResourceKey<Enchantment> key) {
		ItemStack r = rod(e);
		if (r.isEmpty()) return 0;
		var reg = e.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		return reg.get(key).map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, r)).orElse(0);
	}

	private void damageRod(FakePlayerEntity e) {
		ItemStack r = rod(e);
		if (r.isDamageableItem())
			r.hurtAndBreak(1, e, EquipmentSlot.MAINHAND);
	}

	private void dumpFish(FakePlayerEntity e, Container dst) {
		SimpleContainer inv = e.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || stack.getItem() == Items.FISHING_ROD) continue; // keep the rod
			ItemStack rem = HopperBlockEntity.addItem(null, dst, stack, null);
			inv.setItem(i, rem.isEmpty() ? ItemStack.EMPTY : rem);
		}
		dst.setChanged();
	}

	@Override public void onPause(FakePlayerEntity e) { e.getNavigation().stop(); }
	@Override public void onResume(FakePlayerEntity e) {}
	@Override public CompoundTag serialize() {
		CompoundTag t = new CompoundTag(); t.putString("Phase", phase.name()); t.putInt("Caught", caught); t.putLong("WaitUntil", waitUntil); return t;
	}
	@Override public void deserialize(CompoundTag t) {
		if (t == null || t.isEmpty()) return;
		try { phase = Phase.valueOf(t.getStringOr("Phase", "TO_SPOT")); } catch (IllegalArgumentException ignored) {}
		caught = t.getIntOr("Caught", 0); waitUntil = t.getLongOr("WaitUntil", 0L);
	}
}
