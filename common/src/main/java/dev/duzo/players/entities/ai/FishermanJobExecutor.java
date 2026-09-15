package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakeFishingHook;
import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.LavaProofItemEntity;
import dev.duzo.players.entities.OpenWaterProbe;
import dev.duzo.players.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class FishermanJobExecutor implements JobExecutor {
	private enum Phase { TO_SPOT, CAST, WAIT, BITE, REEL, TO_DEPOSIT, DUMP }
	private static final int DEPOSIT_EVERY = 16;     // "every X fishes"
	private static final int BASE_WAIT_TICKS = 20 * 12;
	private static final int BITE_TICKS = 15;        // bobber dips for ~0.75s before reeling
	private static final double SPEED = 1.0;
	private static final double VACUUM_RADIUS = 2.5;

	private Phase phase = Phase.TO_SPOT;
	private int requestCooldown;
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

		if (phase != Phase.DUMP) JobHelpers.closeContainer(level, entity);

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
				ItemStack held = rod(entity);
				if (held.isEmpty()) {
					// raise() scans for a quartermaster before it can dedupe, so ask about once a
					// second rather than paying that scan every tick while blocked
					if (--requestCooldown <= 0) {
						requestCooldown = 20;
						FakePlayerRequests.raise(entity, new ItemStack(Items.FISHING_ROD), FakePlayerRequests.PRIORITY_FAKE);
					}
					return;
				}
				Tackle tackle = Services.TACKLE.read(held);
				BlockPos water = findCastTarget(level, spot, entity, tackle);
				if (water == null) return; // no fishable fluid near the waypoint: idle
				double surfaceY = water.getY() + 0.9;
				Vec3 target = new Vec3(water.getX() + 0.5, surfaceY, water.getZ() + 0.5);
				entity.getLookControl().setLookAt(target.x, target.y, target.z);
				entity.swing(InteractionHand.MAIN_HAND);
				castHook(level, entity, target, surfaceY, level.getFluidState(water).is(FluidTags.LAVA), held);
				int lure = enchant(entity, Enchantments.FISHING_SPEED) + tackle.lureBonus();
				waitUntil = level.getGameTime() + Math.max(20, BASE_WAIT_TICKS - lure * 20 * 5L);
				phase = Phase.WAIT;
			}
			case WAIT -> {
				if (activeHook == null || !activeHook.isAlive()) { phase = Phase.CAST; return; }
				if (!activeHook.isBobbing()) {
					// Clipped terrain or landed short: there is no catch to be had, so recast rather than reel
					// loot out of dry land.
					if (level.getGameTime() >= waitUntil) {
						clearHook();
						phase = Phase.CAST;
					}
					return;
				}
				if (level.getGameTime() >= waitUntil) {
					activeHook.setBiting(true);
					Vec3 b = activeHook.position();
					if (activeHook.isLavaProof() && level.getFluidState(activeHook.blockPosition()).is(FluidTags.LAVA)) {
						level.sendParticles(ParticleTypes.LAVA, b.x, b.y + 0.1, b.z, 4, 0.1, 0.0, 0.1, 0.0);
						level.sendParticles(ParticleTypes.SMOKE, b.x, b.y, b.z, 6, 0.1, 0.0, 0.1, 0.1);
					} else {
						level.sendParticles(ParticleTypes.FISHING, b.x, b.y + 0.1, b.z, 8, 0.1, 0.0, 0.1, 0.0);
						level.sendParticles(ParticleTypes.BUBBLE, b.x, b.y, b.z, 6, 0.1, 0.0, 0.1, 0.1);
					}
					SoundEvent catchSound = Services.TACKLE.read(rod(entity)).catchSound();
					if (catchSound != null) level.playSound(null, b.x, b.y, b.z, catchSound, SoundSource.NEUTRAL, 0.5F, 1.0F);
					// A hook that widens the catchable window (Aquaculture's redstone hook) is deliberately not
					// applied: it only buys a real player reaction time, and a fake never misses a bite, so
					// honouring it would do nothing but make every cast slower.
					biteUntil = level.getGameTime() + BITE_TICKS;
					phase = Phase.BITE;
				}
			}
			case BITE -> { if (level.getGameTime() >= biteUntil) phase = Phase.REEL; }
			case REEL -> {
				if (activeHook == null || !activeHook.isAlive()) {
					// No bobber to reel in. Rolling anyway would fall back to the waypoint, which is the solid
					// block the fisherman stands on, and deny treasure outright.
					clearHook();
					phase = Phase.CAST;
					return;
				}
				entity.swing(InteractionHand.MAIN_HAND);
				Tackle reeled = Services.TACKLE.read(rod(entity));
				Vec3 from = activeHook != null ? activeHook.position() : Vec3.atCenterOf(spot);
				List<ItemStack> drops = rollCatch(level, entity, spot, reeled);
				if (!drops.isEmpty() && reeled.doubleCatchChance() > 0
						&& level.getRandom().nextDouble() <= reeled.doubleCatchChance()) {
					drops = new ArrayList<>(drops);
					drops.addAll(rollCatch(level, entity, spot, reeled));
				}
				for (ItemStack drop : drops) flingCatch(level, entity, from, drop);
				if (!drops.isEmpty()) Services.TACKLE.consumeBait(level, rod(entity));
				clearHook();
				damageRod(entity, reeled);
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
			case TO_DEPOSIT -> { if (JobHelpers.walkTo(entity, deposit, SPEED) == JobHelpers.WalkResult.ARRIVED) phase = Phase.DUMP; }
			case DUMP -> {
				Container dst = JobHelpers.containerAt(level, deposit);
				if (dst == null) { JobHelpers.closeContainer(level, entity); phase = Phase.TO_SPOT; return; }
				if (!JobHelpers.pollContainer(level, entity, deposit)) return; // open + pause ~1s before depositing
				dumpFish(entity, dst);
				restockRod(entity, dst); // grab a fresh rod from the deposit container if ours broke
				phase = Phase.TO_SPOT;
			}
		}
	}

	private void castHook(ServerLevel level, FakePlayerEntity entity, Vec3 target, double surfaceY, boolean lava, ItemStack rod) {
		clearHook();
		// sweep any stray bobbers this fake owns (reload orphans, double-casts) before spawning a new one
		for (FakeFishingHook old : level.getEntitiesOfClass(FakeFishingHook.class,
				entity.getBoundingBox().inflate(64.0), h -> h.getOwner() == entity)) {
			old.discard();
		}
		FakeFishingHook hook = new FakeFishingHook(level, entity);
		hook.setLavaProof(lava);
		hook.setRod(rod);
		double sx = entity.getX(), sy = entity.getEyeY(), sz = entity.getZ();
		hook.setPos(sx, sy, sz);
		hook.aimAt(target, surfaceY);
		Vec3 dir = target.subtract(sx, sy, sz);
		hook.shoot(dir.x, dir.y + 0.3, dir.z, 0.5F, 0.2F);
		level.addFreshEntity(hook);
		activeHook = hook;
	}

	private static final int PUSH_INTO_WATER = 3; // cast this many blocks past the shore, into open water
	private static final int OPEN_WATER_SEARCH = 5;  // how far to look for a spot treasure can actually roll from

	/** Find fishable fluid near the waypoint, then push the target a few blocks further in (away from the fake). */
	private BlockPos findCastTarget(ServerLevel level, BlockPos near, FakePlayerEntity e, Tackle tackle) {
		BlockPos shore = nearestFishableSurface(level, near, tackle);
		if (shore == null) return null;
		double dx = (shore.getX() + 0.5) - e.getX();
		double dz = (shore.getZ() + 0.5) - e.getZ();
		int ux = Math.abs(dx) < 0.3 ? 0 : (int) Math.signum(dx);
		int uz = Math.abs(dz) < 0.3 ? 0 : (int) Math.signum(dz);
		if (ux == 0 && uz == 0) return shore;
		BlockPos best = shore;
		for (int i = 1; i <= PUSH_INTO_WATER; i++) {
			BlockPos cand = shore.offset(ux * i, 0, uz * i);
			if (isFishableSurface(level, cand, tackle)) best = cand; else break;
		}
		if (level.getFluidState(best).is(FluidTags.LAVA)) return best; // lava has no open water rule
		// Vanilla pays treasure out only in open water, a 5x5 column of uncovered source water around the
		// bobber. Pushing straight out from the first shore block the scan happened to find lands the bobber
		// against a bank on anything but a big lake, which silently denies treasure forever, so prefer a spot
		// that passes the test when one is within reach.
		BlockPos open = nearestOpenWater(level, best, tackle);
		return open != null ? open : best;
	}

	/** The closest spot to {@code from} whose bobber column satisfies vanilla's open water test, or null. */
	private BlockPos nearestOpenWater(ServerLevel level, BlockPos from, Tackle tackle) {
		if (isOpenWater(level, from)) return from;
		BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
		for (int r = 1; r <= OPEN_WATER_SEARCH; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // expanding ring
					c.set(from.getX() + dx, from.getY(), from.getZ() + dz);
					if (isFishableSurface(level, c, tackle) && isOpenWater(level, c)) return c.immutable();
				}
			}
		}
		return null;
	}

	/** Nearest fishable surface (source fluid with air above) within a small radius of the waypoint, or null. */
	private BlockPos nearestFishableSurface(ServerLevel level, BlockPos near, Tackle tackle) {
		BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
		for (int r = 0; r <= 4; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // expanding ring
					for (int dy = 3; dy >= -3; dy--) {
						c.set(near.getX() + dx, near.getY() + dy, near.getZ() + dz);
						if (isFishableSurface(level, c, tackle)) return c.immutable();
					}
				}
			}
		}
		return null;
	}

	/** Source fluid only. Flowing water never satisfies the open water test, so aiming at it silently denies treasure. */
	private boolean isFishableSurface(ServerLevel level, BlockPos pos, Tackle tackle) {
		FluidState fluid = level.getFluidState(pos);
		if (!fluid.isSource() || !level.getBlockState(pos.above()).isAir()) return false;
		if (fluid.is(FluidTags.WATER)) return tackle.water();
		if (fluid.is(FluidTags.LAVA)) return tackle.lava();
		return false;
	}

	private void ensureRod(FakePlayerEntity e) {
		if (FishingRods.isFishingRod(e.getMainHandItem())) return;
		ItemStack offhand = e.getOffhandItem();
		if (FishingRods.isFishingRod(offhand)) {
			ItemStack prev = e.getMainHandItem().copy();
			e.setItemSlot(EquipmentSlot.MAINHAND, offhand.copy());
			e.setItemSlot(EquipmentSlot.OFFHAND, prev);
			return;
		}
		SimpleContainer inv = e.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!FishingRods.isFishingRod(inv.getItem(i))) continue;
			ItemStack rod = inv.removeItemNoUpdate(i);
			ItemStack prev = e.getMainHandItem();
			e.setItemSlot(EquipmentSlot.MAINHAND, rod);
			if (!prev.isEmpty()) {
				ItemStack leftover = inv.addItem(prev);
				if (!leftover.isEmpty()) e.spawnAtLocation(leftover);
			}
			return;
		}
	}

	private void flingCatch(ServerLevel level, FakePlayerEntity entity, Vec3 from, ItemStack drop) {
		// A catch reeled out of lava is spawned in the lava, where a normal item entity burns up long
		// before the fisherman can collect it.
		boolean inLava = activeHook != null && activeHook.isLavaProof()
				&& level.getFluidState(activeHook.blockPosition()).is(FluidTags.LAVA);
		ItemEntity item = inLava
				? new LavaProofItemEntity(level, from.x, from.y, from.z, drop)
				: new ItemEntity(level, from.x, from.y, from.z, drop);
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

	/**
	 * Rolls the top-level gameplay/fishing table, which is what fishing mods inject their own species into, so
	 * any of them is picked up with no further work. That table gates treasure behind a fishing_hook predicate,
	 * which our Projectile bobber cannot satisfy, so an {@link OpenWaterProbe} carries the Fisherman's own open
	 * water answer into the loot context as THIS_ENTITY.
	 */
	private List<ItemStack> rollCatch(ServerLevel level, FakePlayerEntity e, BlockPos spot, Tackle tackle) {
		int luck = enchant(e, Enchantments.FISHING_LUCK) + tackle.luckBonus();
		BlockPos bobber = activeHook != null ? activeHook.blockPosition() : spot;
		Vec3 origin = activeHook != null ? activeHook.position() : Vec3.atCenterOf(spot);
		boolean inLava = tackle.lava() && level.getFluidState(bobber).is(FluidTags.LAVA);

		ResourceLocation key = BuiltInLootTables.FISHING;
		if (inLava) {
			ResourceLocation modded = level.dimensionType().hasCeiling() ? tackle.netherTable() : tackle.lavaTable();
			if (modded != null) key = modded;
		}

		OpenWaterProbe probe = new OpenWaterProbe(level, inLava || isOpenWater(level, bobber));
		probe.setPos(origin.x, origin.y, origin.z);

		LootTable table = level.getServer().getLootData().getLootTable(key);
		LootParams params = new LootParams.Builder(level)
			.withParameter(LootContextParams.ORIGIN, origin)
			.withParameter(LootContextParams.TOOL, rod(e))
			.withParameter(LootContextParams.THIS_ENTITY, probe)
			// A real angler would also add their own getLuck(); a fake has no luck attribute, so parity with a
			// player holding the same rod is approximate rather than exact.
			.withLuck(luck)
			.create(LootContextParamSets.FISHING);
		return table.getRandomItems(params);
	}

	private enum WaterCell { ABOVE, INSIDE, INVALID }

	/**
	 * Vanilla's open water test (FishingHook.calculateOpenWater): the 5x5 layers from one below the bobber to two
	 * above must each be uniformly water or uniformly air, water first, so a roof or a wall rules the spot out.
	 * Lily pads count as air and waterlogged plants count as water, exactly as vanilla judges them.
	 */
	private boolean isOpenWater(ServerLevel level, BlockPos bobber) {
		WaterCell previous = WaterCell.INVALID;
		for (int dy = -1; dy <= 2; dy++) {
			WaterCell layer = layerAt(level, bobber.offset(0, dy, 0));
			switch (layer) {
				case ABOVE -> { if (previous == WaterCell.INVALID) return false; }
				case INSIDE -> { if (previous == WaterCell.ABOVE) return false; }
				case INVALID -> { return false; }
			}
			previous = layer;
		}
		return true;
	}

	/** One 5x5 layer, INVALID unless every cell in it agrees. */
	private WaterCell layerAt(ServerLevel level, BlockPos center) {
		WaterCell result = null;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				WaterCell cell = cellAt(level, center.offset(dx, 0, dz));
				if (result == null) result = cell;
				else if (result != cell) return WaterCell.INVALID;
			}
		}
		return result == null ? WaterCell.INVALID : result;
	}

	private WaterCell cellAt(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.is(Blocks.LILY_PAD)) return WaterCell.ABOVE;
		FluidState fluid = state.getFluidState();
		return fluid.is(FluidTags.WATER) && fluid.isSource() && state.getCollisionShape(level, pos).isEmpty()
				? WaterCell.INSIDE : WaterCell.INVALID;
	}

	private ItemStack rod(FakePlayerEntity e) {
		ItemStack main = e.getMainHandItem();
		if (FishingRods.isFishingRod(main)) return main;
		ItemStack offhand = e.getOffhandItem();
		if (FishingRods.isFishingRod(offhand)) return offhand;
		SimpleContainer inv = e.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++)
			if (FishingRods.isFishingRod(inv.getItem(i))) return inv.getItem(i);
		return ItemStack.EMPTY;
	}

	private int enchant(FakePlayerEntity e, Enchantment ench) {
		ItemStack r = rod(e);
		if (r.isEmpty()) return 0;
		return EnchantmentHelper.getItemEnchantmentLevel(ench, r);
	}

	/** Damage the rod in the slot it is actually held in, so break handling fires on the right one. */
	private void damageRod(FakePlayerEntity e, Tackle tackle) {
		if (tackle.durabilitySkipChance() > 0
				&& e.level().getRandom().nextDouble() < tackle.durabilitySkipChance()) return;
		ItemStack main = e.getMainHandItem();
		if (FishingRods.isFishingRod(main)) {
			if (main.isDamageableItem()) main.hurtAndBreak(1, e, ent -> ent.broadcastBreakEvent(EquipmentSlot.MAINHAND));
			return;
		}
		ItemStack offhand = e.getOffhandItem();
		if (FishingRods.isFishingRod(offhand) && offhand.isDamageableItem())
			offhand.hurtAndBreak(1, e, ent -> ent.broadcastBreakEvent(EquipmentSlot.OFFHAND));
	}

	private void dumpFish(FakePlayerEntity e, Container dst) {
		SimpleContainer inv = e.getInventory();
		// Now that any rod counts, refusing to deposit all of them would hoard every spare forever. Keep one
		// only if neither hand already holds one, and deposit the rest.
		boolean keepRod = !FishingRods.isFishingRod(e.getMainHandItem()) && !FishingRods.isFishingRod(e.getOffhandItem());
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (FishingRods.isFishingRod(stack) && keepRod) { keepRod = false; continue; }
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
			if (!FishingRods.isFishingRod(stack)) continue;
			ItemStack one = stack.split(1);
			ItemStack leftover = e.getInventory().addItem(one);
			if (!leftover.isEmpty()) stack.grow(leftover.getCount());
			if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
			chest.setChanged();
			return;
		}
	}

	@Override public void onPause(FakePlayerEntity e) { e.getNavigation().stop(); clearHook(); if (e.level() instanceof ServerLevel sl) JobHelpers.closeContainer(sl, e); }
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
		String name = t.getString("Phase");
		if (!name.isEmpty()) { try { phase = Phase.valueOf(name); } catch (IllegalArgumentException ignored) {} }
		if (phase == Phase.WAIT || phase == Phase.BITE || phase == Phase.REEL) phase = Phase.CAST; // re-cast cleanly; stale hook self-discards
		caught = t.getInt("Caught"); waitUntil = t.getLong("WaitUntil");
	}
}
