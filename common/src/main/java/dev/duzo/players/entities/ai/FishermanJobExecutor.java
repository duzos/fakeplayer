package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakeFishingHook;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
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
	private enum Phase { TO_SPOT, CAST, WAIT, BITE, REEL, TO_DEPOSIT, DUMP }
	private static final int DEPOSIT_EVERY = 16;     // "every X fishes"
	private static final int BASE_WAIT_TICKS = 20 * 12;
	private static final int BITE_TICKS = 15;        // bobber dips for ~0.75s before reeling
	private static final double SPEED = 1.0;
	private static final double VACUUM_RADIUS = 2.5;

	private Phase phase = Phase.TO_SPOT;
	private int caught = 0;
	private long waitUntil = 0L;
	private long biteUntil = 0L;
	private FakeFishingHook activeHook;

	@Override public void tick(ServerLevel level, FakePlayerEntity entity) {
		AIState s = entity.getAIState();
		BlockPos spot = s.waypoint();
		BlockPos deposit = s.depositChest();
		if (spot == null) return; // no spot set: idle (GUI shows "Waypoint unset")

		JobHelpers.vacuum(level, entity, VACUUM_RADIUS); // catch flying back from the bobber lands here
		ensureRod(entity); // a fisherman always holds his rod
		if (activeHook != null && activeHook.isAlive()) faceHook(entity); // always face the bobber while it's out

		switch (phase) {
			case TO_SPOT -> {
				double cx = spot.getX() + 0.5, cy = spot.getY() + 1, cz = spot.getZ() + 0.5;
				double dx = entity.getX() - cx, dz = entity.getZ() - cz;
				if (dx * dx + dz * dz <= 1.0) {
					entity.getNavigation().stop();
					entity.setPos(cx, cy, cz); // sit EXACTLY on top of the waypoint block, not near it
					entity.setPhysicalState(FakePlayerEntity.PhysicalState.SITTING);
					phase = Phase.CAST;
				} else if (entity.getNavigation().isDone()) {
					entity.getNavigation().moveTo(cx, cy, cz, SPEED);
				}
			}
			case CAST -> {
				if (rod(entity).isEmpty()) return; // no rod anywhere: idle
				BlockPos water = findCastTarget(level, spot, entity);
				if (water == null) return; // no water near the waypoint: idle
				double surfaceY = water.getY() + 0.9;
				Vec3 target = new Vec3(water.getX() + 0.5, surfaceY, water.getZ() + 0.5);
				entity.getLookControl().setLookAt(target.x, target.y, target.z);
				entity.swing(InteractionHand.MAIN_HAND);
				castHook(level, entity, target, surfaceY);
				int lure = enchant(entity, Enchantments.LURE);
				waitUntil = level.getGameTime() + Math.max(20, BASE_WAIT_TICKS - lure * 20 * 5L);
				phase = Phase.WAIT;
			}
			case WAIT -> {
				if (activeHook == null || !activeHook.isAlive()) { phase = Phase.CAST; return; }
				if (level.getGameTime() >= waitUntil) {
					activeHook.setBiting(true);
					Vec3 b = activeHook.position();
					level.sendParticles(ParticleTypes.FISHING, b.x, b.y + 0.1, b.z, 8, 0.1, 0.0, 0.1, 0.0);
					level.sendParticles(ParticleTypes.BUBBLE, b.x, b.y, b.z, 6, 0.1, 0.0, 0.1, 0.1);
					biteUntil = level.getGameTime() + BITE_TICKS;
					phase = Phase.BITE;
				}
			}
			case BITE -> { if (level.getGameTime() >= biteUntil) phase = Phase.REEL; }
			case REEL -> {
				entity.swing(InteractionHand.MAIN_HAND);
				Vec3 from = activeHook != null ? activeHook.position() : Vec3.atCenterOf(spot);
				for (ItemStack drop : rollCatch(level, entity, spot)) flingCatch(level, entity, from, drop);
				clearHook();
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
				restockRod(entity, dst); // grab a fresh rod from the deposit container if ours broke
				phase = Phase.TO_SPOT;
			}
		}
	}

	private void castHook(ServerLevel level, FakePlayerEntity entity, Vec3 target, double surfaceY) {
		clearHook();
		// sweep any stray bobbers this fake owns (reload orphans, double-casts) before spawning a new one
		for (FakeFishingHook old : level.getEntitiesOfClass(FakeFishingHook.class,
				entity.getBoundingBox().inflate(64.0), h -> h.getOwner() == entity)) {
			old.discard();
		}
		FakeFishingHook hook = new FakeFishingHook(level, entity);
		double sx = entity.getX(), sy = entity.getEyeY(), sz = entity.getZ();
		hook.setPos(sx, sy, sz);
		hook.aimAt(target, surfaceY);
		Vec3 dir = target.subtract(sx, sy, sz);
		hook.shoot(dir.x, dir.y + 0.3, dir.z, 0.5F, 0.2F);
		level.addFreshEntity(hook);
		activeHook = hook;
	}

	private static final int PUSH_INTO_WATER = 3; // cast this many blocks past the shore, into open water

	/** Find water near the waypoint, then push the target a few blocks further in (away from the fake). */
	private BlockPos findCastTarget(ServerLevel level, BlockPos near, FakePlayerEntity e) {
		BlockPos shore = nearestWaterSurface(level, near);
		if (shore == null) return null;
		double dx = (shore.getX() + 0.5) - e.getX();
		double dz = (shore.getZ() + 0.5) - e.getZ();
		int ux = Math.abs(dx) < 0.3 ? 0 : (int) Math.signum(dx);
		int uz = Math.abs(dz) < 0.3 ? 0 : (int) Math.signum(dz);
		if (ux == 0 && uz == 0) return shore;
		BlockPos best = shore;
		for (int i = 1; i <= PUSH_INTO_WATER; i++) {
			BlockPos cand = shore.offset(ux * i, 0, uz * i);
			if (isWaterSurface(level, cand)) best = cand; else break;
		}
		return best;
	}

	/** Nearest water surface (water with air above) within a small radius of the waypoint, or null. */
	private BlockPos nearestWaterSurface(ServerLevel level, BlockPos near) {
		BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
		for (int r = 0; r <= 4; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // expanding ring
					for (int dy = 3; dy >= -3; dy--) {
						c.set(near.getX() + dx, near.getY() + dy, near.getZ() + dz);
						if (isWaterSurface(level, c)) return c.immutable();
					}
				}
			}
		}
		return null;
	}

	private boolean isWaterSurface(ServerLevel level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.WATER) && level.getBlockState(pos.above()).isAir();
	}

	private void ensureRod(FakePlayerEntity e) {
		if (e.getMainHandItem().getItem() == Items.FISHING_ROD) return;
		SimpleContainer inv = e.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).getItem() != Items.FISHING_ROD) continue;
			ItemStack rod = inv.removeItemNoUpdate(i);
			ItemStack prev = e.getMainHandItem();
			e.setItemSlot(EquipmentSlot.MAINHAND, rod);
			if (!prev.isEmpty()) {
				ItemStack leftover = inv.addItem(prev);
				if (!leftover.isEmpty()) e.spawnAtLocation((ServerLevel) e.level(), leftover);
			}
			return;
		}
	}

	private void flingCatch(ServerLevel level, FakePlayerEntity entity, Vec3 from, ItemStack drop) {
		ItemEntity item = new ItemEntity(level, from.x, from.y, from.z, drop);
		double dx = entity.getX() - from.x, dy = entity.getEyeY() - from.y, dz = entity.getZ() - from.z;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		item.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.max(0.1, dist * 0.08), dz * 0.1);
		item.setPickUpDelay(10);
		level.addFreshEntity(item);
	}

	private void clearHook() {
		if (activeHook != null) { activeHook.discard(); activeHook = null; }
	}

	private void faceHook(FakePlayerEntity e) {
		double dx = activeHook.getX() - e.getX();
		double dz = activeHook.getZ() - e.getZ();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		e.setYRot(yaw);
		e.setYBodyRot(yaw);
		e.yHeadRot = yaw;
		e.getLookControl().setLookAt(activeHook.getX(), activeHook.getY(), activeHook.getZ());
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

	/** If the fake has no rod (ours broke), pull one from the deposit container. */
	private void restockRod(FakePlayerEntity e, Container chest) {
		if (!rod(e).isEmpty()) return;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack stack = chest.getItem(i);
			if (stack.getItem() != Items.FISHING_ROD) continue;
			ItemStack one = stack.split(1);
			ItemStack leftover = e.getInventory().addItem(one);
			if (!leftover.isEmpty()) stack.grow(leftover.getCount());
			if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
			chest.setChanged();
			return;
		}
	}

	@Override public void onPause(FakePlayerEntity e) { e.getNavigation().stop(); clearHook(); }
	@Override public void onResume(FakePlayerEntity e) {
		// (re)start: re-check the waypoint and walk to it instead of resuming mid-cast at the old spot
		clearHook();
		phase = Phase.TO_SPOT;
		e.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
	}
	@Override public CompoundTag serialize() {
		CompoundTag t = new CompoundTag(); t.putString("Phase", phase.name()); t.putInt("Caught", caught); t.putLong("WaitUntil", waitUntil); return t;
	}
	@Override public void deserialize(CompoundTag t) {
		if (t == null || t.isEmpty()) return;
		try { phase = Phase.valueOf(t.getStringOr("Phase", "TO_SPOT")); } catch (IllegalArgumentException ignored) {}
		if (phase == Phase.WAIT || phase == Phase.BITE || phase == Phase.REEL) phase = Phase.CAST; // re-cast cleanly; stale hook self-discards
		caught = t.getIntOr("Caught", 0); waitUntil = t.getLongOr("WaitUntil", 0L);
	}
}
