package dev.duzo.players.entities.ai;

import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.requests.Commission;
import dev.duzo.players.entities.ai.requests.CraftPlan;
import dev.duzo.players.entities.ai.requests.PoolIndex;
import dev.duzo.players.entities.ai.requests.RecipeGraph;
import dev.duzo.players.entities.ai.requests.RequestRouting;
import dev.duzo.players.entities.ai.requests.SenderAlerts;
import dev.duzo.players.entities.ai.requests.StockReserve;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CrafterJobExecutor implements JobExecutor {
	private static final double SPEED = 1.0;
	// How many sets' worth of each ingredient to buffer before a crafting run.
	private static final int BATCH = 8;
	// Ticks per ingredient placed into the grid (0.1s at 20 tps).
	private static final int PLACE_TICKS = 2;

	// Consecutive unreachable walks before the crafter reports and waits, as the miner, farmer and lumberjack do.
	private static final int MAX_PATH_FAIL = 3;
	private static final int RETRY_WAIT_TICKS = 20 * 15;

	private enum Phase { TO_SOURCE, PULL, TO_TABLE, CRAFT, TO_DEPOSIT, DUMP }

	/** A commission runs its own legs: collect from the storeroom, craft, carry the results back. */
	private enum CommissionPhase { TO_POOL, TO_TABLE, CRAFT, RETURN }

	private CommissionPhase commissionPhase = CommissionPhase.TO_POOL;
	private int runsDone;
	// which step the run counter belongs to, so re-entering a step does not redo its finished runs
	private int activeCursor = -1;

	private Phase phase = Phase.TO_SOURCE;
	private int craftIndex;
	private int craftTimer = PLACE_TICKS;
	private int pathFails;
	private long waitUntil;
	private String lastBlocker = "";

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		AIState state = entity.getAIState();
		Commission commission = Commission.of(state);
		if (commission != null) {
			// commissioned work outranks the standing recipe, and needs only a table: the
			// storeroom stands in for both the source and the deposit container
			tickCommission(level, entity, commission);
			return;
		}
		resetCommissionLegs();

		BlockPos source = state.sourceChest();
		BlockPos deposit = state.depositChest();
		BlockPos table = state.waypoint();
		if (source == null || deposit == null || table == null) return; // needs source, table and deposit

		CompoundTag recipe = state.jobParams().getCompoundOrEmpty("Recipe");
		if (recipe.isEmpty()) return; // nothing taught yet

		List<Item> placeOrder = readPlaceOrder(recipe);
		if (placeOrder.isEmpty()) return;
		Map<Item, Integer> need = new LinkedHashMap<>();
		for (Item it : placeOrder) need.merge(it, 1, Integer::sum);
		ItemStack out = readOut(recipe, entity);
		if (out.isEmpty()) return;

		if (level.getGameTime() < waitUntil) return; // reported an unreachable marker, waiting before retrying

		SimpleContainer inv = entity.getInventory();

		// Only the source/deposit polling phases hold a container open; everything else closes it.
		if (phase != Phase.PULL && phase != Phase.DUMP) JobHelpers.closeContainer(level, entity);

		switch (phase) {
			case TO_SOURCE -> {
				if (walk(level, entity, source, "the source container")) phase = Phase.PULL;
			}
			case PULL -> {
				Container src = JobHelpers.containerAt(level, source);
				if (src == null) {
					JobHelpers.closeContainer(level, entity);
					entity.getNavigation().stop();
					if (hasOutputs(inv, need)) phase = Phase.TO_DEPOSIT;
					return;
				}
				if (!JobHelpers.atTarget(entity, source)) { JobHelpers.closeContainer(level, entity); phase = Phase.TO_SOURCE; return; }
				if (!JobHelpers.pollContainer(level, entity, source)) return; // open + pause ~1s before pulling
				int moved = JobHelpers.inventoryFull(entity) ? 0 : pullNeeded(src, inv, need);
				if (moved == 0) {
					if (hasFullSet(inv, need)) phase = Phase.TO_TABLE;
					else if (hasOutputs(inv, need)) phase = Phase.TO_DEPOSIT;
					else entity.getNavigation().stop(); // source dry, nothing to craft or bank: idle and poll
				}
			}
			case TO_TABLE -> {
				if (walk(level, entity, table, "the crafting table")) {
					if (!craftingTableNear(level, table)) { entity.getNavigation().stop(); return; } // no table here: idle
					craftIndex = 0;
					craftTimer = PLACE_TICKS;
					phase = Phase.CRAFT;
				}
			}
			case CRAFT -> {
				if (!JobHelpers.atTarget(entity, table)) { phase = Phase.TO_TABLE; return; }
				if (!hasFullSet(inv, need)) { entity.setDisplayItem(ItemStack.EMPTY); phase = Phase.TO_DEPOSIT; return; }
				if (--craftTimer > 0) return;
				if (craftIndex < placeOrder.size()) {
					// Show the ingredient being placed and swing, one cell every 0.1s. Purely visual -
					// the real main hand is never touched, so there is nothing here to stash or restore.
					entity.setDisplayItem(new ItemStack(placeOrder.get(craftIndex)));
					entity.swing(InteractionHand.MAIN_HAND);
					craftIndex++;
					craftTimer = PLACE_TICKS;
					return;
				}
				// Whole grid placed: assemble one result, then craft another set or go deposit.
				consumeSet(inv, need);
				ItemStack rem = inv.addItem(out.copy());
				if (!rem.isEmpty()) entity.spawnAtLocation(level, rem);
				craftIndex = 0;
				if (hasFullSet(inv, need) && JobHelpers.canAccept(inv, out)) {
					craftTimer = PLACE_TICKS;
				} else {
					entity.setDisplayItem(ItemStack.EMPTY);
					phase = Phase.TO_DEPOSIT;
				}
			}
			case TO_DEPOSIT -> {
				if (walk(level, entity, deposit, "the deposit container")) phase = Phase.DUMP;
			}
			case DUMP -> {
				Container dst = JobHelpers.containerAt(level, deposit);
				if (dst == null) { JobHelpers.closeContainer(level, entity); phase = Phase.TO_SOURCE; return; }
				if (!JobHelpers.atTarget(entity, deposit)) { JobHelpers.closeContainer(level, entity); phase = Phase.TO_DEPOSIT; return; }
				if (!JobHelpers.pollContainer(level, entity, deposit)) return; // open + pause ~1s before depositing
				int moved = dumpOutputs(inv, dst, need, StockReserve.of(entity));
				if (moved == 0) phase = Phase.TO_SOURCE;
			}
		}
	}

	/**
	 * Serve a Quartermaster's commission: collect what the step needs from its storeroom, craft it
	 * at this Crafter's own table, and carry the results back to the pool.
	 *
	 * <p>Intermediate results are kept in the Crafter's inventory rather than banked between steps,
	 * so a chain such as logs to planks to a chest spends one trip per step and one at the end.
	 */
	private void tickCommission(ServerLevel level, FakePlayerEntity entity, Commission commission) {
		BlockPos table = entity.getAIState().waypoint();
		if (table == null) {
			// commissioned only when a table was marked, so this means it was cleared since
			abandon(level, entity, commission, "notable",
					"has no crafting table marked, so it cannot fill its commission");
			return;
		}
		if (level.getGameTime() < waitUntil) return;
		JobHelpers.closeContainer(level, entity);

		FakePlayerEntity qm = quartermasterOf(level, commission);
		if (qm == null) {
			// unobservable is not gone: an unloaded storeroom must not throw away a commission,
			// but one that really has gone would leave this crafter marked busy forever
			if (level.getGameTime() - commission.since() > RequestRouting.ORPHAN_TICKS) {
				abandon(level, entity, commission, "noqm",
						"lost contact with its quartermaster and has stopped crafting");
			} else {
				entity.getNavigation().stop();
			}
			return;
		}

		Commission.Entry step = commission.current();
		RecipeGraph.Node node = step == null
				? null : RecipeGraph.byKey(level.getServer(), step.recipe());
		if (step == null || node == null) {
			abandon(level, entity, commission, "norecipe",
					"was asked for a recipe this world no longer has");
			return;
		}

		// only a genuinely new step restarts the run counter. Re-entering TO_TABLE mid-step, which
		// happens whenever the owner takes ingredients out, would otherwise redo the runs already
		// finished and over-draw the pool by that much every time.
		if (activeCursor != commission.cursor()) {
			activeCursor = commission.cursor();
			runsDone = 0;
		}

		switch (commissionPhase) {
			case TO_POOL -> {
				if (holdsAll(entity, step, runsLeft(step))) {
					commissionPhase = CommissionPhase.TO_TABLE;
					return;
				}
				if (!walk(level, entity, qm.blockPosition(), "the storeroom")) return;
				Collected got = collect(level, entity, qm, step);
				if (got == Collected.SHORT) {
					abandon(level, entity, commission, "shortpool",
							"could not collect what it needs to craft " + commission.goal()
									+ ", the storeroom has changed");
					return;
				}
				if (got == Collected.FULL) {
					abandon(level, entity, commission, "crafterfull",
							"has no room to carry what " + commission.goal() + " needs");
					return;
				}
				commissionPhase = CommissionPhase.TO_TABLE;
			}
			case TO_TABLE -> {
				if (!walk(level, entity, table, "the crafting table")) return;
				if (!craftingTableNear(level, table)) {
					entity.getNavigation().stop();
					return; // no table at the marker: idle rather than throw the commission away
				}
				craftIndex = 0;
				craftTimer = PLACE_TICKS;
				commissionPhase = CommissionPhase.CRAFT;
			}
			case CRAFT -> craftRun(level, entity, commission, node, step, table);
			case RETURN -> {
				if (!walk(level, entity, qm.blockPosition(), "the storeroom")) return;
				bank(level, entity, qm, commission);
				// cleared last, so an interrupted return leaves the commission in hand along with
				// the goods, rather than a freed crafter holding a storeroom's worth of stock
				if (!Commission.clear(entity)) {
					// the orders would survive and the whole step would run again, drawing the pool
					// down each cycle, so stop rather than loop
					stall(level, entity);
					return;
				}
				resetCommissionLegs();
			}
		}
	}

	/** One run of the commissioned recipe, placed cell by cell so it reads as crafting. */
	private void craftRun(ServerLevel level, FakePlayerEntity entity, Commission commission,
	                      RecipeGraph.Node node, Commission.Entry step, BlockPos table) {
		if (!JobHelpers.atTarget(entity, table)) { commissionPhase = CommissionPhase.TO_TABLE; return; }
		if (!holdsAll(entity, step, 1)) {
			// something took the ingredients back out of this fake, so go and collect again
			entity.setDisplayItem(ItemStack.EMPTY);
			commissionPhase = CommissionPhase.TO_POOL;
			return;
		}
		if (!JobHelpers.canAccept(entity.getInventory(), node.result())) {
			// full, so bank what has been made and let the quartermaster plan the rest. Crafting on
			// would drop the result on the floor, which is stock the storeroom paid for.
			entity.setDisplayItem(ItemStack.EMPTY);
			commissionPhase = CommissionPhase.RETURN;
			return;
		}
		if (--craftTimer > 0) return;

		List<Identifier> order = CraftPlan.placement(node, step.perRun());
		if (craftIndex < order.size()) {
			Item shown = BuiltInRegistries.ITEM.getOptional(order.get(craftIndex)).orElse(null);
			if (shown != null) entity.setDisplayItem(new ItemStack(shown));
			entity.swing(InteractionHand.MAIN_HAND);
			craftIndex++;
			craftTimer = PLACE_TICKS;
			return;
		}

		consumeNeeds(entity.getInventory(), step.perRun(), 1);
		// the recipe's own result stack, so a result carrying data components keeps them
		ItemStack rem = entity.getInventory().addItem(node.result().copy());
		if (!rem.isEmpty()) entity.spawnAtLocation(level, rem);
		craftIndex = 0;
		craftTimer = PLACE_TICKS;
		runsDone++;
		if (runsDone < step.times()) return;

		entity.setDisplayItem(ItemStack.EMPTY);
		if (commission.cursor() + 1 < commission.steps().size()) {
			if (!Commission.advance(entity, commission)) {
				// the cursor did not move, so carrying on would re-run this step for ever
				stall(level, entity);
				return;
			}
			commissionPhase = CommissionPhase.TO_POOL;
		} else {
			commissionPhase = CommissionPhase.RETURN;
		}
	}

	/** Outcome of a collection leg. */
	private enum Collected { OK, SHORT, FULL }

	/**
	 * Take what the rest of this step needs out of the pool, counting what is already carried.
	 *
	 * <p>Nothing is kept unless everything is collected: the pool has already been debited by the
	 * time a later item comes up short, so every stack taken goes back rather than only the one
	 * that failed.
	 */
	private Collected collect(ServerLevel level, FakePlayerEntity entity, FakePlayerEntity qm,
	                          Commission.Entry step) {
		ServerLevel qmLevel = (ServerLevel) qm.level();
		int runs = runsLeft(step);
		List<ItemStack> taken = new ArrayList<>();
		Collected result = Collected.OK;

		for (Map.Entry<Identifier, Integer> need : step.perRun().entrySet()) {
			int owed = need.getValue() * runs - countId(entity.getInventory(), need.getKey());
			if (owed <= 0) continue;
			List<ItemStack> got = FakePlayerRequests.withdraw(qmLevel, qm, need.getKey(), owed);
			int moved = 0;
			for (ItemStack stack : got) {
				moved += stack.getCount();
				taken.add(stack);
			}
			if (moved < owed) result = Collected.SHORT;
		}

		if (result == Collected.OK) {
			for (ItemStack stack : taken) {
				ItemStack rem = entity.getInventory().addItem(stack);
				if (!rem.isEmpty()) {
					// put the untaken part back on the stack so the giveBack loop below sees it all
					stack.setCount(rem.getCount());
					result = Collected.FULL;
					break;
				}
				stack.setCount(0);
			}
		}
		if (result != Collected.OK) {
			for (ItemStack stack : taken) giveBack(level, entity, qmLevel, qm, stack);
		}
		return result;
	}

	/** Runs of this step still to do. */
	private int runsLeft(Commission.Entry step) {
		return Math.max(1, step.times() - runsDone);
	}

	/**
	 * Put the commission's results and its leftover ingredients into the pool. Only what this
	 * commission actually deals in: sweeping by recipe tag banked the Crafter's own buffered logs
	 * into someone else's storeroom.
	 */
	private void bank(ServerLevel level, FakePlayerEntity entity, FakePlayerEntity qm, Commission commission) {
		ServerLevel qmLevel = (ServerLevel) qm.level();
		Set<Identifier> theirs = commission.itemsInvolved();
		SimpleContainer inv = entity.getInventory();
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.isEmpty()) continue;
			if (!theirs.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) continue;
			FakePlayerRequests.deposit(qmLevel, qm, stack);
			if (stack.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
		}
		inv.setChanged();
	}

	/**
	 * Give the commission up. What the storeroom paid for goes back to it first, so a cancelled
	 * commission never strands stock in a fake that is about to bank it somewhere else.
	 */
	private void abandon(ServerLevel level, FakePlayerEntity entity, Commission commission,
	                     String kind, String reason) {
		entity.setDisplayItem(ItemStack.EMPTY);
		entity.getNavigation().stop();
		FakePlayerEntity qm = quartermasterOf(level, commission);
		if (qm != null) bank(level, entity, qm, commission);
		resetCommissionLegs();
		if (!Commission.clear(entity)) {
			stall(level, entity);
			return;
		}
		// deduped per kind: an escalation that keeps failing re-commissions on a timer, and an
		// unlatched message there is a chat line every cycle for as long as the pool is short
		SenderAlerts.alert(level, entity, kind, reason);
	}

	/** The commissioning Quartermaster, or null while it is unloaded, gone or re-jobbed. */
	@Nullable
	private FakePlayerEntity quartermasterOf(ServerLevel level, Commission commission) {
		if (!(level.getEntity(commission.quartermaster()) instanceof FakePlayerEntity qm)) return null;
		if (!qm.isAlive() || !FPJobs.is(qm.getAIState().jobId(), FPJobs.QUARTERMASTER)) return null;
		return qm;
	}

	/**
	 * The orders could not be written, so there is no safe way to go on: re-running the step would
	 * draw the pool down every cycle. Stand still and say so, once.
	 */
	private void stall(ServerLevel level, FakePlayerEntity entity) {
		entity.getNavigation().stop();
		entity.setDisplayItem(ItemStack.EMPTY);
		waitUntil = level.getGameTime() + RETRY_WAIT_TICKS;
		SenderAlerts.alert(level, entity, "orderstoobig",
				"could not update its crafting orders and has stopped until its state shrinks");
	}

	/**
	 * Put a withdrawn stack back. The pool can be full too, and a stack quietly discarded here is
	 * stock the player watched a fake take out of a chest and never saw again, so drop the rest.
	 */
	private void giveBack(ServerLevel level, FakePlayerEntity entity, ServerLevel qmLevel,
	                      FakePlayerEntity qm, ItemStack stack) {
		if (stack.isEmpty()) return;
		FakePlayerRequests.deposit(qmLevel, qm, stack);
		if (!stack.isEmpty()) entity.spawnAtLocation(level, stack);
	}

	private void resetCommissionLegs() {
		commissionPhase = CommissionPhase.TO_POOL;
		runsDone = 0;
		activeCursor = -1;
	}

	/** Whether the Crafter holds enough for this many runs of the step. */
	private boolean holdsAll(FakePlayerEntity entity, Commission.Entry step, int runs) {
		for (Map.Entry<Identifier, Integer> need : step.perRun().entrySet()) {
			if (countId(entity.getInventory(), need.getKey()) < need.getValue() * runs) return false;
		}
		return true;
	}

	private int countId(SimpleContainer inv, Identifier item) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).equals(item)) n += s.getCount();
		}
		return n;
	}

	private void consumeNeeds(SimpleContainer inv, Map<Identifier, Integer> needs, int runs) {
		for (Map.Entry<Identifier, Integer> need : needs.entrySet()) {
			int remaining = need.getValue() * runs;
			for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty() || !BuiltInRegistries.ITEM.getKey(s.getItem()).equals(need.getKey())) continue;
				int take = Math.min(remaining, s.getCount());
				s.shrink(take);
				remaining -= take;
				if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
			}
		}
	}

	/**
	 * One leg of a walk. True once arrived. An unreachable target is reported and waited out rather than repathed
	 * every tick, which at a high pathRange is an expensive search to repeat.
	 */
	private boolean walk(ServerLevel level, FakePlayerEntity entity, BlockPos target, String what) {
		JobHelpers.WalkResult result = JobHelpers.walkTo(entity, target, SPEED);
		if (result == JobHelpers.WalkResult.ARRIVED) {
			pathFails = 0;
			lastBlocker = "";
			return true;
		}
		if (result == JobHelpers.WalkResult.UNREACHABLE && ++pathFails >= MAX_PATH_FAIL) {
			pathFails = 0;
			entity.getNavigation().stop();
			String message = "crafter: cannot reach " + what;
			if (!message.equals(lastBlocker)) entity.sendChat(message + " - waiting 15s before retry");
			lastBlocker = message;
			waitUntil = level.getGameTime() + RETRY_WAIT_TICKS;
		}
		return false;
	}

	/** A crafting table at, or directly adjacent to, the marked spot. */
	private boolean craftingTableNear(ServerLevel level, BlockPos center) {
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int dz = -1; dz <= 1; dz++)
					if (level.getBlockState(center.offset(dx, dy, dz)).is(Blocks.CRAFTING_TABLE)) return true;
		return false;
	}

	/** The learned grid as an ordered list of items, one per filled cell. */
	private List<Item> readPlaceOrder(CompoundTag recipe) {
		List<Item> order = new ArrayList<>();
		ListTag grid = recipe.getListOrEmpty("Grid");
		for (int i = 0; i < grid.size(); i++) {
			String id = grid.getStringOr(i, "");
			if (id.isEmpty()) continue;
			Identifier rid = Identifier.tryParse(id);
			if (rid == null) continue;
			Item item = BuiltInRegistries.ITEM.getValue(rid);
			if (item == Items.AIR) continue;
			order.add(item);
		}
		return order;
	}

	private ItemStack readOut(CompoundTag recipe, FakePlayerEntity entity) {
		CompoundTag outTag = recipe.getCompoundOrEmpty("Out");
		if (outTag.isEmpty()) return ItemStack.EMPTY;
		var ops = entity.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		return ItemStack.CODEC.parse(ops, outTag).result().orElse(ItemStack.EMPTY);
	}

	private boolean hasFullSet(SimpleContainer inv, Map<Item, Integer> need) {
		for (Map.Entry<Item, Integer> e : need.entrySet())
			if (countItem(inv, e.getKey()) < e.getValue()) return false;
		return true;
	}

	private boolean hasOutputs(SimpleContainer inv, Map<Item, Integer> need) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && !need.containsKey(s.getItem())) return true;
		}
		return false;
	}

	private int countItem(SimpleContainer inv, Item item) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && s.getItem() == item) n += s.getCount();
		}
		return n;
	}

	private void consumeSet(SimpleContainer inv, Map<Item, Integer> need) {
		for (Map.Entry<Item, Integer> e : need.entrySet()) {
			int remaining = e.getValue();
			for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty() || s.getItem() != e.getKey()) continue;
				int take = Math.min(remaining, s.getCount());
				s.shrink(take);
				remaining -= take;
				if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
			}
		}
	}

	/** Pull one source stack of a needed ingredient that we have not yet buffered a batch of. */
	private int pullNeeded(Container src, SimpleContainer inv, Map<Item, Integer> need) {
		for (int i = 0; i < src.getContainerSize(); i++) {
			ItemStack stack = src.getItem(i);
			if (stack.isEmpty()) continue;
			Integer per = need.get(stack.getItem());
			if (per == null) continue;
			if (countItem(inv, stack.getItem()) >= per * BATCH) continue;
			ItemStack remainder = HopperBlockEntity.addItem(src, inv, stack.copy(), null);
			int taken = stack.getCount() - remainder.getCount();
			if (taken > 0) {
				stack.shrink(taken);
				if (stack.isEmpty()) src.setItem(i, ItemStack.EMPTY);
				src.setChanged();
				inv.setChanged();
				return 1;
			}
		}
		return 0;
	}

	/** Push one stack of anything that is not a recipe ingredient (the crafted output, plus container leftovers). */
	private int dumpOutputs(SimpleContainer inv, Container dst, Map<Item, Integer> need, StockReserve reserve) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || need.containsKey(stack.getItem())) continue;
			if (JobHelpers.depositSurplus(inv, i, dst, reserve)) continue;
			ItemStack remainder = HopperBlockEntity.addItem(inv, dst, stack.copy(), null);
			int moved = stack.getCount() - remainder.getCount();
			if (moved > 0) {
				stack.shrink(moved);
				if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
				inv.setChanged();
				dst.setChanged();
				return 1;
			}
		}
		return 0;
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
		entity.setDisplayItem(ItemStack.EMPTY);
		if (entity.level() instanceof ServerLevel sl) JobHelpers.closeContainer(sl, entity);
	}

	@Override
	public void onResume(FakePlayerEntity entity) {}

	@Override
	public CompoundTag serialize() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Phase", phase.name());
		return tag;
	}

	@Override
	public void deserialize(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) return;
		String name = tag.getStringOr("Phase", "");
		if (!name.isEmpty()) {
			try { phase = Phase.valueOf(name); } catch (IllegalArgumentException ignored) {}
		}
	}
}
