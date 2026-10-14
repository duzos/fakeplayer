package dev.duzo.players.entities.ai;

import dev.duzo.players.Constants;
import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.RequestKey;
import dev.duzo.players.api.requests.RequesterKind;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.builder.BlockBreaker;
import dev.duzo.players.entities.ai.builder.BuildPlan;
import dev.duzo.players.entities.ai.builder.BuilderParams;
import dev.duzo.players.entities.ai.builder.MaterialRules;
import dev.duzo.players.entities.ai.builder.PlacePass;
import dev.duzo.players.entities.ai.builder.SchematicStore;
import dev.duzo.players.entities.ai.claims.WorkClaims;
import dev.duzo.players.entities.ai.requests.SenderAlerts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a schematic in stripes. The world is the progress record: a cell is done when the world block
 * matches, so a reload or a second Builder rescans instead of resuming from a saved cursor.
 */
public class BuilderJobExecutor implements JobExecutor {
	public enum Phase { INIT, CLAIM, STOCK, WORK, DEPOSIT, BLOCKED, DONE }

	private enum Step { WAIT, PLACED, SKIP, BLOCKED }

	private static final double REACH = 4.5;
	private static final double STAND_REACH = 4.0;
	private static final int CLAIM_TTL = 200;
	private static final int RENEW_EVERY = 20;
	private static final int RECHECK_TICKS = 100;
	private static final int CLAIM_RETRY_TICKS = 40;
	private static final int MAX_PATH_FAIL = 3;
	private static final String KIND_BLOCKED = "builder-blocked";
	// jobState rides in the synced AIState, which is size limited
	private static final int MAX_PERSISTED = 32;

	private Phase phase = Phase.INIT;
	private Phase returnPhase = Phase.WORK;
	private @Nullable BuildPlan plan;
	private boolean clear = true;
	private int stripeIdx = -1;
	private @Nullable BoundingBox claim;
	private int cursor;
	private boolean finishing;
	private boolean stocked;
	private boolean depositTried;
	private boolean announced;
	private int pathFail;
	private long nextActionTick;
	private long nextRenew;
	private long nextCheck;

	private final BlockBreaker breaker = new BlockBreaker();
	private @Nullable BlockPos stand;
	private @Nullable BlockPos standFor;
	private long standDeadline;
	private int overlapFails;

	// per pass over the stripe, so a pass that places nothing can say why
	private boolean passProgress;
	private boolean sawUnloaded;
	private int deferred;
	private int unreachable;
	private @Nullable String obstruction;
	private final Map<Item, Integer> shortNeed = new LinkedHashMap<>();

	// items the source container was found not to hold, so WORK does not walk back for them every cell
	private final Set<Item> dry = new HashSet<>();
	private final Set<Integer> stuck = new HashSet<>();

	private Map<Item, Integer> blocked = new LinkedHashMap<>();
	private String blockedMsg = "";
	private String alertKey = "";
	private boolean alertedBlocked;
	private boolean alertedDone;
	private final Set<Identifier> requested = new HashSet<>();

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		if (phase == Phase.DONE) return;
		long now = level.getGameTime();
		if (phase != Phase.DEPOSIT && phase != Phase.STOCK) JobHelpers.closeContainer(level, entity);
		if (phase != Phase.WORK && !entity.getDisplayItem().isEmpty()) entity.setDisplayItem(ItemStack.EMPTY);

		if (claim != null && now >= nextRenew) {
			nextRenew = now + RENEW_EVERY;
			if (!WorkClaims.renew(level, entity.getUUID(), claim, CLAIM_TTL)
					&& !WorkClaims.tryClaim(level, entity.getUUID(), "builder", claim, CLAIM_TTL)) {
				dropClaim(level, entity);
				phase = plan == null ? Phase.INIT : Phase.CLAIM;
			}
		}

		Constants.trace(entity.getUUID() + "/builder", "builder {} phase={} stripe={} claim={} blocked={}",
				entity.getName().getString(), phase, stripeIdx, claim, blocked.keySet());
		switch (phase) {
			case INIT -> init(level, entity);
			case CLAIM -> claim(level, entity);
			case STOCK -> stock(level, entity);
			case WORK -> work(level, entity);
			case DEPOSIT -> deposit(level, entity);
			case BLOCKED -> tickBlocked(level, entity);
			case DONE -> {}
		}
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
		entity.setDisplayItem(ItemStack.EMPTY);
		if (entity.level() instanceof ServerLevel sl) {
			JobHelpers.closeContainer(sl, entity);
			breaker.reset(sl, entity);
		}
		// a paused or stopped Builder stops renewing anyway, so give the stripe back for others to take now
		WorkClaims.releaseAll(entity.getUUID());
		claim = null;
		stripeIdx = -1;
		if (phase != Phase.DONE) phase = Phase.INIT;
	}

	@Override
	public void onResume(FakePlayerEntity entity) {
		// started again after finishing: rescan, so damage to the build gets mended
		if (phase == Phase.DONE) {
			phase = Phase.INIT;
			alertedDone = false;
		}
	}

	@Override
	public CompoundTag serialize() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Phase", phase.ordinal());
		tag.putInt("Stripe", stripeIdx);
		if (claim != null) {
			tag.putIntArray("Claim", new int[] {claim.minX(), claim.minY(), claim.minZ(), claim.maxX(), claim.maxY(), claim.maxZ()});
		}
		tag.putBoolean("Finishing", finishing);
		ListTag list = new ListTag();
		for (Map.Entry<Item, Integer> e : blocked.entrySet()) {
			if (list.size() >= MAX_PERSISTED) break;
			CompoundTag c = new CompoundTag();
			c.putString("Id", BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
			c.putInt("N", e.getValue());
			list.add(c);
		}
		tag.put("Blocked", list);
		tag.putString("BlockedMsg", blockedMsg);
		tag.putString("AlertKey", alertKey);
		tag.putBoolean("AlertedBlocked", alertedBlocked);
		tag.putBoolean("AlertedDone", alertedDone);
		ListTag req = new ListTag();
		for (Identifier id : requested) {
			if (req.size() >= MAX_PERSISTED) break;
			CompoundTag c = new CompoundTag();
			c.putString("Id", id.toString());
			req.add(c);
		}
		tag.put("Requested", req);
		return tag;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) return;
		// every phase but DONE restarts from INIT: the plan is rebuilt and the world rescanned
		int saved = tag.getIntOr("Phase", 0);
		phase = saved == Phase.DONE.ordinal() ? Phase.DONE : Phase.INIT;
		stripeIdx = tag.getIntOr("Stripe", -1);
		int[] box = tag.getIntArray("Claim").orElse(null);
		claim = box != null && box.length == 6 ? new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]) : null;
		finishing = tag.getBooleanOr("Finishing", false);
		blocked = new LinkedHashMap<>();
		ListTag list = tag.getList("Blocked").orElseGet(ListTag::new);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag c = list.getCompoundOrEmpty(i);
			Identifier id = Identifier.tryParse(c.getStringOr("Id", ""));
			if (id == null) continue;
			BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> blocked.put(item, c.getIntOr("N", 0)));
		}
		blockedMsg = tag.getStringOr("BlockedMsg", "");
		alertKey = tag.getStringOr("AlertKey", "");
		alertedBlocked = tag.getBooleanOr("AlertedBlocked", false);
		alertedDone = tag.getBooleanOr("AlertedDone", false);
		requested.clear();
		ListTag req = tag.getList("Requested").orElseGet(ListTag::new);
		for (int i = 0; i < req.size(); i++) {
			Identifier id = Identifier.tryParse(req.getCompoundOrEmpty(i).getStringOr("Id", ""));
			if (id != null) requested.add(id);
		}
	}

	private void init(ServerLevel level, FakePlayerEntity entity) {
		BuilderParams p = BuilderParams.read(entity.getAIState());
		if (!p.ready()) {
			block(level, entity, "setup", "builder: set a schematic name and an anchor first", Map.of());
			return;
		}
		clear = p.clear();
		BuildPlan.BuildKey key = new BuildPlan.BuildKey(level.dimension(), p.schematic().trim(), p.anchor(), p.rotation(), p.mirror(), p.uploader());
		// a blocked Builder rechecks often, so the plan is only fetched again when what it builds has changed
		if (plan == null || !plan.key.equals(key) || plan.stripeWidth != PlayersConfig.get().builderStripeWidth) {
			SchematicStore.Result<BuildPlan> r = BuildPlan.get(level, key);
			if (!r.ok()) {
				plan = null;
				block(level, entity, "setup", "builder: " + r.error(), Map.of());
				return;
			}
			plan = r.value();
		}
		if (plan.stripes.isEmpty()) {
			block(level, entity, "setup", "builder: " + p.schematic() + " has nothing to place", Map.of());
			return;
		}
		if (!announced) {
			announced = true;
			entity.sendChat("builder: building " + p.schematic() + ", " + plan.cellCount() + " blocks");
			if (!plan.unsupported.isEmpty()) {
				int n = plan.unsupported.values().stream().mapToInt(Integer::intValue).sum();
				entity.sendChat("builder: cannot place " + n + " blocks of this schematic (fluids, unbreakable or itemless blocks)");
			}
		}
		dry.clear();
		pathFail = 0;
		if (claim != null && stripeIdx >= 0 && stripeIdx < plan.stripes.size()
				&& plan.stripes.get(stripeIdx).box().equals(claim)
				&& WorkClaims.tryClaim(level, entity.getUUID(), "builder", claim, CLAIM_TTL)) {
			beginStripe();
			return;
		}
		claim = null;
		stripeIdx = -1;
		phase = Phase.CLAIM;
		nextCheck = 0;
	}

	private void claim(ServerLevel level, FakePlayerEntity entity) {
		long now = level.getGameTime();
		if (now < nextCheck) return;
		List<BuildPlan.Stripe> stripes = plan.stripes;
		int n = stripes.size();
		int offset = Math.floorMod(entity.getUUID().hashCode(), n);
		boolean anyPending = false;
		boolean anyOpen = false;
		for (int k = 0; k < n; k++) {
			int i = (offset + k) % n;
			BuildPlan.Stripe stripe = stripes.get(i);
			if (pendingCount(level, stripe) == 0) continue;
			anyPending = true;
			if (stuck.contains(i)) continue;
			anyOpen = true;
			if (WorkClaims.tryClaim(level, entity.getUUID(), "builder", stripe.box(), CLAIM_TTL)) {
				claim = stripe.box();
				stripeIdx = i;
				nextRenew = now + RENEW_EVERY;
				depositTried = false;
				beginStripe();
				return;
			}
		}
		if (!anyPending) {
			finish(level, entity);
		} else if (!anyOpen) {
			// every stripe left is one this Builder could not move on
			block(level, entity, alertKey.isEmpty() ? "stuck" : alertKey,
					blockedMsg.isEmpty() ? "builder: nothing more can be placed" : blockedMsg, blocked);
		} else {
			nextCheck = now + CLAIM_RETRY_TICKS;
		}
	}

	private void beginStripe() {
		cursor = 0;
		stocked = false;
		resetPass();
		phase = Phase.STOCK;
	}

	private void resetPass() {
		passProgress = false;
		sawUnloaded = false;
		deferred = 0;
		unreachable = 0;
		obstruction = null;
		shortNeed.clear();
	}

	private void stock(ServerLevel level, FakePlayerEntity entity) {
		BuildPlan.Stripe stripe = plan.stripes.get(stripeIdx);
		SimpleContainer inv = entity.getInventory();
		Map<Item, Integer> shortfall = shortfall(level, stripe, inv);
		if (shortfall.isEmpty() || stocked) {
			if (shortfall.isEmpty()) settle(level, entity);
			phase = Phase.WORK;
			return;
		}
		BlockPos src = entity.getAIState().sourceChest();
		if (src == null) {
			dry.addAll(shortfall.keySet());
			stocked = true;
			return;
		}
		boolean room = false;
		for (Item item : shortfall.keySet()) room |= JobHelpers.roomFor(inv, new ItemStack(item)) > 0;
		if (!room && !depositTried && entity.getAIState().depositChest() != null) {
			depositTried = true;
			returnPhase = Phase.STOCK;
			phase = Phase.DEPOSIT;
			return;
		}
		JobHelpers.WalkResult walk = JobHelpers.walkTo(entity, src, 1.0);
		if (walk != JobHelpers.WalkResult.ARRIVED) {
			if (walk == JobHelpers.WalkResult.UNREACHABLE && ++pathFail >= MAX_PATH_FAIL) {
				pathFail = 0;
				dry.addAll(shortfall.keySet());
				stocked = true;
			}
			return;
		}
		Container container = JobHelpers.containerAt(level, src);
		if (container == null) {
			dry.addAll(shortfall.keySet());
			stocked = true;
			return;
		}
		if (!JobHelpers.pollContainer(level, entity, src)) return;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack peek = container.getItem(slot);
			if (peek.isEmpty() || !peek.getComponentsPatch().isEmpty()) continue;
			Integer want = shortfall.get(peek.getItem());
			if (want == null || want <= 0) continue;
			int moved = JobHelpers.withdrawInto(container, slot, want, inv);
			if (moved > 0) shortfall.merge(peek.getItem(), -moved, Integer::sum);
		}
		stocked = true;
		JobHelpers.closeContainer(level, entity);
		for (Map.Entry<Item, Integer> e : shortfall.entrySet()) {
			if (e.getValue() > 0) dry.add(e.getKey());
		}
	}

	/** What the unfinished cells of a stripe still cost, less what the fake carries. */
	private Map<Item, Integer> shortfall(ServerLevel level, BuildPlan.Stripe stripe, SimpleContainer inv) {
		Map<Item, Integer> need = new LinkedHashMap<>();
		for (BuildPlan.Cell c : stripe.cells()) {
			if (!loaded(level, c) || isDone(level, c)) continue;
			if (PlacePass.matches(c.state(), level.getBlockState(c.pos()))) continue;
			MaterialRules.Cost cost = MaterialRules.cost(c.state());
			if (cost.count() > 0) need.merge(cost.item(), cost.count(), Integer::sum);
		}
		Map<Item, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<Item, Integer> e : need.entrySet()) {
			int lack = e.getValue() - count(inv, e.getKey());
			if (lack > 0) out.put(e.getKey(), lack);
		}
		return out;
	}

	private void work(ServerLevel level, FakePlayerEntity entity) {
		BuildPlan.Stripe stripe = plan.stripes.get(stripeIdx);
		List<BuildPlan.Cell> cells = stripe.cells();
		long now = level.getGameTime();
		while (cursor < cells.size()) {
			BuildPlan.Cell c = cells.get(cursor);
			if (!loaded(level, c)) {
				sawUnloaded = true;
				cursor++;
				continue;
			}
			if (isDone(level, c)) {
				cursor++;
				continue;
			}
			Step step = stepCell(level, entity, c, now);
			switch (step) {
				case WAIT, BLOCKED -> {
					return;
				}
				case PLACED -> {
					cursor++;
					passProgress = true;
					settle(level, entity);
					return;
				}
				case SKIP -> cursor++;
			}
		}
		endOfPass(level, entity);
	}

	private void endOfPass(ServerLevel level, FakePlayerEntity entity) {
		boolean pending = deferred > 0 || unreachable > 0 || sawUnloaded || obstruction != null || !shortNeed.isEmpty();
		if (!pending) {
			// claim() decides by the world, so a stripe is only complete when the world agrees
			int left = pendingCount(level, plan.stripes.get(stripeIdx));
			if (left > 0) {
				deferred = left;
				pending = true;
			}
		}
		if (!pending) {
			completeStripe(level, entity);
			return;
		}
		if (passProgress) {
			cursor = 0;
			resetPass();
			return;
		}
		stuck.add(stripeIdx);
		if (obstruction != null) {
			block(level, entity, obstruction, obstruction, Map.of());
		} else if (!shortNeed.isEmpty()) {
			Map<Item, Integer> lack = new LinkedHashMap<>();
			for (Map.Entry<Item, Integer> e : shortNeed.entrySet()) {
				int n = e.getValue() - count(entity.getInventory(), e.getKey());
				if (n > 0) lack.put(e.getKey(), n);
			}
			block(level, entity, "short", shortMessage(lack), lack);
		} else if (unreachable > 0) {
			block(level, entity, "unreachable", "builder: cannot reach " + unreachable + " blocks of the build", Map.of());
		} else if (deferred > 0) {
			block(level, entity, "deferred", "builder: " + deferred + " blocks cannot be placed, nothing to hold them", Map.of());
		} else {
			block(level, entity, "unloaded", "builder: part of the build is in unloaded chunks", Map.of());
		}
	}

	private Step stepCell(ServerLevel level, FakePlayerEntity entity, BuildPlan.Cell c, long now) {
		BlockPos pos = c.pos();
		BlockState have = level.getBlockState(pos);
		boolean mainDone = matchesCell(c.state(), have);
		BlockPos partnerPos = c.partnerPos();
		BlockState partnerHave = partnerPos == null ? null : level.getBlockState(partnerPos);
		boolean partnerDone = partnerPos == null || matchesCell(c.partnerState(), partnerHave);

		// whatever occupies a target has to go before anything is put there, and a cell meant to be empty
		// has nothing to place over a replaceable block such as grass, so that is broken too
		if (!mainDone && (c.state().isAir() || !overwritable(have))) return breakIn(level, entity, pos, have);
		if (partnerPos != null && !partnerDone && !overwritable(partnerHave)) return breakIn(level, entity, partnerPos, partnerHave);

		BlockPos first = mainDone ? partnerPos : pos;
		BlockState firstState = mainDone ? c.partnerState() : c.state();
		if (first == null || firstState == null || firstState.isAir()) return Step.SKIP;

		if (!firstState.canSurvive(level, first)
				|| (firstState.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(first.below())))) {
			deferred++;
			return Step.SKIP;
		}

		MaterialRules.Cost cost = MaterialRules.cost(c.state());
		if (!mainDone && cost.count() > 0 && count(entity.getInventory(), cost.item()) < cost.count()) {
			BlockPos src = entity.getAIState().sourceChest();
			if (src != null && !dry.contains(cost.item())) {
				stocked = false;
				phase = Phase.STOCK;
				return Step.BLOCKED;
			}
			shortNeed.merge(cost.item(), cost.count(), Integer::sum);
			return Step.SKIP;
		}

		if (!otherEntitiesClear(level, entity, first, firstState)) {
			deferred++;
			return Step.SKIP;
		}
		Reach reach = reach(level, entity, first, firstState, now);
		if (reach == Reach.UNREACHABLE) {
			unreachable++;
			return Step.SKIP;
		}
		if (reach == Reach.MOVING) return Step.WAIT;
		if (now < nextActionTick) return Step.WAIT;

		entity.getLookControl().setLookAt(first.getX() + 0.5, first.getY() + 0.5, first.getZ() + 0.5);
		entity.swing(InteractionHand.MAIN_HAND);
		ItemStack shown = new ItemStack(cost.count() > 0 ? cost.item() : firstState.getBlock().asItem());
		if (!ItemStack.isSameItemSameComponents(entity.getDisplayItem(), shown)) entity.setDisplayItem(shown);

		if (!mainDone) {
			if (!place(level, entity, pos, c.state())) {
				deferred++;
				return Step.SKIP;
			}
			if (cost.count() > 0) consume(entity.getInventory(), cost.item(), cost.count());
		}
		if (partnerPos != null && !partnerDone) place(level, entity, partnerPos, c.partnerState());
		nextActionTick = now + Math.max(1, PlayersConfig.get().builderPlaceTicks);
		return Step.PLACED;
	}

	private boolean place(ServerLevel level, FakePlayerEntity entity, BlockPos pos, BlockState want) {
		BlockState state = want;
		if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
			// dry unless a water source is already there, so no water is conjured
			state = state.setValue(BlockStateProperties.WATERLOGGED, level.getFluidState(pos).is(Fluids.WATER));
		}
		// leaves a Builder puts down must not decay
		if (state.hasProperty(BlockStateProperties.PERSISTENT)) state = state.setValue(BlockStateProperties.PERSISTENT, true);
		// claim-protection events are not fired here, as with the other jobs
		if (!level.setBlock(pos, state, 3)) return false;
		SoundType sound = state.getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		level.gameEvent(entity, GameEvent.BLOCK_PLACE, pos);
		return true;
	}

	private Step breakIn(ServerLevel level, FakePlayerEntity entity, BlockPos pos, BlockState have) {
		if (have.hasBlockEntity() || have.getDestroySpeed(level, pos) < 0.0F) {
			if (obstruction == null) {
				obstruction = "builder: " + have.getBlock().getName().getString() + " at " + pos.getX() + ", " + pos.getY() + ", "
						+ pos.getZ() + " is in the way and will not be broken";
			}
			return Step.SKIP;
		}
		long now = level.getGameTime();
		// claim-protection events are not fired for this break, as with the other jobs
		Reach reach = reach(level, entity, pos, null, now);
		if (reach == Reach.UNREACHABLE) {
			unreachable++;
			return Step.SKIP;
		}
		if (reach == Reach.MOVING) return Step.WAIT;
		BlockBreaker.Result r = breaker.tick(level, entity, pos);
		switch (r) {
			case PROGRESS:
				return Step.WAIT;
			case BROKEN:
				passProgress = true;
				nextActionTick = now + Math.max(1, PlayersConfig.get().builderPlaceTicks);
				return Step.WAIT;
			case FULL:
				if (entity.getAIState().depositChest() != null && !depositTried) {
					depositTried = true;
					returnPhase = Phase.WORK;
					phase = Phase.DEPOSIT;
					return Step.BLOCKED;
				}
				block(level, entity, "full", "builder: inventory full and nowhere to put the rubble", Map.of());
				return Step.BLOCKED;
			default:
				return Step.WAIT;
		}
	}

	private enum Reach { READY, MOVING, UNREACHABLE }

	private Reach reach(ServerLevel level, FakePlayerEntity entity, BlockPos pos, @Nullable BlockState placing, long now) {
		Vec3 center = pos.getCenter();
		boolean inReach = entity.getEyePosition().distanceToSqr(center) <= REACH * REACH;
		boolean overlaps = placing != null && overlapsSelf(level, entity, pos, placing);
		if (inReach && !overlaps) {
			entity.getNavigation().stop();
			stand = null;
			standFor = null;
			overlapFails = 0;
			return Reach.READY;
		}
		if (stand == null || !pos.equals(standFor) || !JobHelpers.canStandAt(level, stand)) {
			stand = findStand(level, entity, pos);
			standFor = pos.immutable();
			if (stand == null) return Reach.UNREACHABLE;
			// a long walk is allowed to take long
			standDeadline = now + 100 + (long) (Math.sqrt(entity.blockPosition().distSqr(stand)) * 12);
		}
		boolean atStand = Math.abs(entity.getX() - (stand.getX() + 0.5)) < 0.6 && Math.abs(entity.getZ() - (stand.getZ() + 0.5)) < 0.6
				&& Math.abs(entity.getY() - stand.getY()) < 1.0;
		if (atStand || now > standDeadline) {
			// standing where it meant to and still unable to act, or never getting there
			stand = null;
			standFor = null;
			entity.getNavigation().stop();
			if (++overlapFails >= 2) {
				overlapFails = 0;
				return Reach.UNREACHABLE;
			}
			return Reach.MOVING;
		}
		if (!JobHelpers.moveToChecked(entity, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0)) {
			stand = null;
			standFor = null;
			if (++pathFail >= MAX_PATH_FAIL) {
				pathFail = 0;
				return Reach.UNREACHABLE;
			}
		}
		return Reach.MOVING;
	}

	private @Nullable BlockPos findStand(ServerLevel level, FakePlayerEntity entity, BlockPos pos) {
		Vec3 center = pos.getCenter();
		double eye = entity.getEyeHeight();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		int r = 4;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -4; dy <= 2; dy++) {
					// feet or head inside the target cell would be in the way of the block itself
					if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) continue;
					BlockPos feet = pos.offset(dx, dy, dz);
					if (!level.hasChunkAt(feet)) continue;
					double ex = feet.getX() + 0.5 - center.x;
					double ey = feet.getY() + eye - center.y;
					double ez = feet.getZ() + 0.5 - center.z;
					if (ex * ex + ey * ey + ez * ez > STAND_REACH * STAND_REACH) continue;
					if (!JobHelpers.canStandAt(level, feet)) continue;
					double dist = feet.distToCenterSqr(entity.getX(), entity.getY(), entity.getZ());
					if (dist < bestDist) {
						bestDist = dist;
						best = feet;
					}
				}
			}
		}
		return best;
	}

	private boolean overlapsSelf(ServerLevel level, FakePlayerEntity entity, BlockPos pos, BlockState placing) {
		VoxelShape shape = placing.getCollisionShape(level, pos);
		return !shape.isEmpty() && entity.getBoundingBox().intersects(shape.bounds().move(pos));
	}

	/** Another player or mob standing where the block goes. The fake itself is handled by stepping aside. */
	private boolean otherEntitiesClear(ServerLevel level, FakePlayerEntity self, BlockPos pos, BlockState state) {
		VoxelShape shape = state.getCollisionShape(level, pos);
		if (shape.isEmpty()) return true;
		AABB box = shape.bounds().move(pos);
		return level.getEntities((Entity) null, box, e -> e != self && !e.isSpectator() && e.blocksBuilding).isEmpty();
	}

	private static boolean overwritable(BlockState have) {
		return have.isAir() || have.getBlock() instanceof LiquidBlock || have.canBeReplaced();
	}

	private boolean matchesCell(@Nullable BlockState want, BlockState have) {
		if (want == null) return true;
		if (want.isAir() && !clear) return true;
		return PlacePass.matches(want, have);
	}

	/** Whether the chunks of a cell and of its partner are loaded, so reading either does not load one. */
	private static boolean loaded(ServerLevel level, BuildPlan.Cell c) {
		return level.hasChunkAt(c.pos()) && (c.partnerPos() == null || level.hasChunkAt(c.partnerPos()));
	}

	private boolean isDone(ServerLevel level, BuildPlan.Cell c) {
		if (!loaded(level, c)) return false;
		if (!matchesCell(c.state(), level.getBlockState(c.pos()))) return false;
		return c.partnerPos() == null || matchesCell(c.partnerState(), level.getBlockState(c.partnerPos()));
	}

	private int pendingCount(ServerLevel level, BuildPlan.Stripe stripe) {
		int n = 0;
		for (BuildPlan.Cell c : stripe.cells()) {
			if (!loaded(level, c) || !isDone(level, c)) n++;
		}
		return n;
	}

	private void completeStripe(ServerLevel level, FakePlayerEntity entity) {
		dropClaim(level, entity);
		stuck.clear();
		settle(level, entity);
		phase = Phase.CLAIM;
		nextCheck = 0;
	}

	private void dropClaim(ServerLevel level, FakePlayerEntity entity) {
		if (claim != null) WorkClaims.release(entity.getUUID(), claim);
		claim = null;
		stripeIdx = -1;
		breaker.reset(level, entity);
		entity.getNavigation().stop();
		stand = null;
		standFor = null;
	}

	private void finish(ServerLevel level, FakePlayerEntity entity) {
		finishing = true;
		settle(level, entity);
		if (entity.getAIState().depositChest() != null && hasSurplus(entity)) {
			returnPhase = Phase.DONE;
			depositTried = false;
			phase = Phase.DEPOSIT;
			return;
		}
		complete(level, entity);
	}

	private void complete(ServerLevel level, FakePlayerEntity entity) {
		phase = Phase.DONE;
		finishing = false;
		WorkClaims.releaseAll(entity.getUUID());
		claim = null;
		JobHelpers.closeContainer(level, entity);
		entity.setDisplayItem(ItemStack.EMPTY);
		if (!alertedDone) {
			alertedDone = true;
			entity.sendChat("builder: build complete");
		}
		entity.mutateAIState(s -> s.setRunning(false));
	}

	/** Progress has been made or the stripe is fully stocked, so any earlier problem is over. */
	private void settle(ServerLevel level, FakePlayerEntity entity) {
		if (alertedBlocked || !blocked.isEmpty() || !alertKey.isEmpty()) {
			alertedBlocked = false;
			alertKey = "";
			blockedMsg = "";
			blocked = new LinkedHashMap<>();
			SenderAlerts.clear(entity, KIND_BLOCKED);
		}
		cancelRequests(level, entity);
	}

	private void block(ServerLevel level, FakePlayerEntity entity, String key, String message, Map<Item, Integer> shortfall) {
		dropClaim(level, entity);
		phase = Phase.BLOCKED;
		nextCheck = level.getGameTime() + RECHECK_TICKS;
		blocked = new LinkedHashMap<>(shortfall);
		String msg = message;
		if (!shortfall.isEmpty()) msg = message + (raiseShortfall(level, entity, shortfall) ? ", requested from a quartermaster"
				: ", put them in the supply chest");
		blockedMsg = msg;
		if (!alertedBlocked || !key.equals(alertKey)) {
			alertKey = key;
			alertedBlocked = SenderAlerts.alert(level, entity, KIND_BLOCKED, msg);
		}
	}

	private void tickBlocked(ServerLevel level, FakePlayerEntity entity) {
		long now = level.getGameTime();
		if (now < nextCheck) return;
		nextCheck = now + RECHECK_TICKS;
		if (!blocked.isEmpty()) {
			boolean needed = stillNeeded(level);
			Constants.trace(entity.getUUID() + "/builder/blocked", "builder {} blocked on {} stillNeeded={}",
					entity.getName().getString(), blocked.keySet(), needed);
			if (!needed) {
				// another Builder on the same site placed what this one was waiting for
				settle(level, entity);
			} else if (!shortfallResolved(level, entity)) {
				raiseShortfall(level, entity, blocked);
				return;
			}
		}
		stuck.clear();
		phase = Phase.INIT;
	}

	/** Whether any unfinished cell of the whole build still costs one of the items this fake is blocked on. */
	private boolean stillNeeded(ServerLevel level) {
		if (plan == null) return true;
		for (BuildPlan.Stripe stripe : plan.stripes) {
			for (BuildPlan.Cell c : stripe.cells()) {
				if (!loaded(level, c)) return true;
				if (isDone(level, c) || PlacePass.matches(c.state(), level.getBlockState(c.pos()))) continue;
				if (blocked.containsKey(MaterialRules.cost(c.state()).item())) return true;
			}
		}
		return false;
	}

	/** Whether any of what was missing has turned up, in the inventory from a Runner or in the supply chest. */
	private boolean shortfallResolved(ServerLevel level, FakePlayerEntity entity) {
		BlockPos src = entity.getAIState().sourceChest();
		Container container = src == null ? null : JobHelpers.containerAt(level, src);
		for (Item item : blocked.keySet()) {
			if (count(entity.getInventory(), item) > 0) return true;
			if (container == null) continue;
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				if (plain(container.getItem(slot), item)) return true;
			}
		}
		return false;
	}

	/** @return whether every item is now on a quartermaster's board. */
	private boolean raiseShortfall(ServerLevel level, FakePlayerEntity entity, Map<Item, Integer> shortfall) {
		boolean all = true;
		for (Map.Entry<Item, Integer> e : shortfall.entrySet()) {
			if (e.getValue() <= 0) continue;
			var raised = FakePlayerRequests.raise(entity, new ItemStack(e.getKey(), e.getValue()), FakePlayerRequests.PRIORITY_FAKE);
			if (raised.result().accepted()) requested.add(BuiltInRegistries.ITEM.getKey(e.getKey()));
			else all = false;
		}
		return all;
	}

	private void cancelRequests(ServerLevel level, FakePlayerEntity entity) {
		if (requested.isEmpty()) return;
		UUID owner = entity.getAIState().ownerUUID();
		if (owner != null) {
			for (Identifier id : requested) {
				RequestKey key = new RequestKey(entity.getUUID(), RequesterKind.FAKE, entity.getAIState().jobId(), id);
				FakePlayerRequests.cancel(level, entity, owner, key);
			}
		}
		requested.clear();
	}

	private static String shortMessage(Map<Item, Integer> lack) {
		StringBuilder sb = new StringBuilder("builder: out of ");
		int i = 0;
		for (Map.Entry<Item, Integer> e : lack.entrySet()) {
			if (i++ == 4) {
				sb.append(", and ").append(lack.size() - 4).append(" more");
				break;
			}
			if (i > 1) sb.append(", ");
			sb.append(e.getValue()).append(" ").append(new ItemStack(e.getKey()).getHoverName().getString());
		}
		return sb.toString();
	}

	private void deposit(ServerLevel level, FakePlayerEntity entity) {
		BlockPos chest = entity.getAIState().depositChest();
		if (chest == null) {
			leaveDeposit(level, entity);
			return;
		}
		JobHelpers.WalkResult walk = JobHelpers.walkTo(entity, chest, 1.0);
		if (walk != JobHelpers.WalkResult.ARRIVED) {
			JobHelpers.closeContainer(level, entity);
			if (walk == JobHelpers.WalkResult.UNREACHABLE && ++pathFail >= MAX_PATH_FAIL) {
				pathFail = 0;
				block(level, entity, "deposit-unreachable", "builder: cannot reach the deposit container", Map.of());
			}
			return;
		}
		Container container = JobHelpers.containerAt(level, chest);
		if (container == null) {
			block(level, entity, "deposit-gone", "builder: deposit container gone", Map.of());
			return;
		}
		if (!JobHelpers.pollContainer(level, entity, chest)) return;
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!keep(inv.getItem(i))) JobHelpers.moveSlot(inv, i, container);
		}
		JobHelpers.closeContainer(level, entity);
		if (hasSurplus(entity) && finishing) {
			block(level, entity, "deposit-full", "builder: deposit container full", Map.of());
			return;
		}
		leaveDeposit(level, entity);
	}

	private void leaveDeposit(ServerLevel level, FakePlayerEntity entity) {
		pathFail = 0;
		if (returnPhase == Phase.DONE || finishing) complete(level, entity);
		else phase = returnPhase;
	}

	private boolean hasSurplus(FakePlayerEntity entity) {
		SimpleContainer inv = entity.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!inv.getItem(i).isEmpty() && !keep(inv.getItem(i))) return true;
		}
		return false;
	}

	/** Food and tools stay with the fake; so do the materials of an unfinished build. */
	private boolean keep(ItemStack stack) {
		if (stack.isEmpty()) return true;
		if (stack.has(DataComponents.FOOD) || stack.isDamageableItem()) return true;
		return !finishing && plan != null && plan.bom.containsKey(stack.getItem());
	}

	/** A stack with no data on it, so it is the plain block: no shulker contents, name or enchantments. */
	private static boolean plain(ItemStack stack, Item item) {
		return stack.is(item) && stack.getComponentsPatch().isEmpty();
	}

	private static int count(SimpleContainer inv, Item item) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (plain(s, item)) n += s.getCount();
		}
		return n;
	}

	private static void consume(SimpleContainer inv, Item item, int amount) {
		int left = amount;
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (!plain(s, item)) continue;
			int take = Math.min(left, s.getCount());
			s.shrink(take);
			left -= take;
			if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
		}
		inv.setChanged();
	}

	/** Cancels this fake's open Builder requests, from the persisted list so it works with no live executor. */
	public static void cancelOutstanding(FakePlayerEntity entity) {
		if (!(entity.level() instanceof ServerLevel level)) return;
		AIState state = entity.getAIState();
		UUID owner = state.ownerUUID();
		if (owner == null || !FPJobs.is(state.jobId(), FPJobs.BUILDER)) return;
		entity.flushJobState();
		ListTag req = entity.getAIState().jobState().getList("Requested").orElseGet(ListTag::new);
		for (int i = 0; i < req.size(); i++) {
			Identifier id = Identifier.tryParse(req.getCompoundOrEmpty(i).getStringOr("Id", ""));
			if (id == null) continue;
			FakePlayerRequests.cancel(level, entity, owner, new RequestKey(entity.getUUID(), RequesterKind.FAKE, state.jobId(), id));
		}
	}
}
