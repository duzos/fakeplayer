package dev.duzo.players.entities.ai.requests;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What it would take to craft something, worked out against a copy of a storage pool.
 *
 * <p>Plans nothing into the world: the pool it spends is a map, so a plan that turns out to be
 * short has cost nothing and can be reported whole. Existing stock is preferred over crafting,
 * only the shortfall is crafted, and over-crafting feeds its surplus back to the step that asked,
 * so two parents wanting the same intermediate share one run of it.
 *
 * <p>It collects <b>every</b> shortfall rather than stopping at the first, which is the difference
 * between telling the owner what the build needs and telling them one item at a time.
 */
@ApiStatus.Internal
public final class CraftPlan {
	/** Deep enough for any real chain, shallow enough that a pathological pack cannot hang a tick. */
	private static final int MAX_DEPTH = 12;
	/** Bounded by what a Commission can carry, so a plan is never made that cannot be dispatched. */
	public static final int MAX_STEPS = 16;

	/**
	 * Run this recipe this many times, spending exactly these ingredients per run.
	 *
	 * <p>The choice is carried rather than recomputed later. A cell that accepts a tag is resolved
	 * against the pool as it looked while planning, and the pool has moved on by the time a Crafter
	 * collects: recomputing there picks a different plank and strands the commission.
	 */
	public record Step(Identifier recipe, int times, Map<Identifier, Integer> perRun) {}

	private final List<Step> steps;
	private final Map<Identifier, Integer> missing;

	private CraftPlan(List<Step> steps, Map<Identifier, Integer> missing) {
		this.steps = List.copyOf(steps);
		this.missing = Map.copyOf(missing);
	}

	public List<Step> steps() { return steps; }

	/** Item to how many units could not be sourced. Empty when the plan can actually be run. */
	public Map<Identifier, Integer> missing() { return missing; }

	public boolean complete() {
		return missing.isEmpty() && !steps.isEmpty() && steps.size() <= MAX_STEPS;
	}

	/**
	 * True when nothing could be planned at all, as opposed to planned and found short. There is no
	 * missing list worth showing then, so the ordinary empty-storeroom message reads better.
	 */
	public boolean unplannable() {
		return missing.isEmpty() && !complete();
	}

	/** "4 oak planks, 1 stick", for the one message the owner gets. */
	public String describeMissing() {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<Identifier, Integer> entry : missing.entrySet()) {
			if (!out.isEmpty()) out.append(", ");
			out.append(entry.getValue()).append(' ').append(entry.getKey());
		}
		return out.toString();
	}

	/**
	 * @param stock what the pool holds now. Copied, never mutated.
	 */
	public static CraftPlan of(MinecraftServer server, Map<Identifier, Integer> stock, Identifier want, int count) {
		Planner planner = new Planner(server, new HashMap<>(stock));
		planner.solve(want, Math.max(0, count), 0);
		return new CraftPlan(planner.steps, planner.missing);
	}

	/** The item each occupied cell is filled with, in placement order, for an agreed choice. */
	public static List<Identifier> placement(RecipeGraph.Node node, Map<Identifier, Integer> chosen) {
		Map<Identifier, Integer> left = new LinkedHashMap<>(chosen);
		List<Identifier> order = new ArrayList<>(node.cells().size());
		for (RecipeGraph.Choice cell : node.cells()) {
			Identifier pick = cell.options().get(0);
			for (Identifier option : cell.options()) {
				if (left.getOrDefault(option, 0) > 0) {
					pick = option;
					break;
				}
			}
			left.merge(pick, -1, Integer::sum);
			order.add(pick);
		}
		return order;
	}

	private static final class Planner {
		private final MinecraftServer server;
		private final Map<Identifier, Integer> pool;
		private final List<Step> steps = new ArrayList<>();
		private final Map<Identifier, Integer> missing = new LinkedHashMap<>();
		private final Set<Identifier> visiting = new HashSet<>();

		private Planner(MinecraftServer server, Map<Identifier, Integer> pool) {
			this.server = server;
			this.pool = pool;
		}

		private void solve(Identifier item, int count, int depth) {
			int owed = count - take(item, count);
			if (owed <= 0) return;

			// an item already on this path would recurse forever, and a chain this deep is a pack
			// doing something the planner has no business unpicking either way
			if (depth >= MAX_DEPTH || !visiting.add(item)) {
				missing.merge(item, owed, Integer::sum);
				return;
			}
			try {
				RecipeGraph.Node node = pick(item);
				if (node == null || steps.size() >= MAX_STEPS) {
					missing.merge(item, owed, Integer::sum);
					return;
				}
				// not Math.ceilDiv: the 1.20.x branches build on Java 17, which does not have it
				int runs = (owed + node.yield() - 1) / node.yield();
				Map<Identifier, Integer> total = needs(node, runs, pool);
				for (Map.Entry<Identifier, Integer> need : total.entrySet()) {
					solve(need.getKey(), need.getValue(), depth + 1);
				}
				// checked again on the way out: steps are appended once the recursion returns, so a
				// subtree can push the count past the cap between the guard above and this line
				if (steps.size() >= MAX_STEPS) {
					missing.merge(item, owed, Integer::sum);
					return;
				}
				Map<Identifier, Integer> perRun = new LinkedHashMap<>();
				for (Map.Entry<Identifier, Integer> need : total.entrySet()) {
					perRun.put(need.getKey(), need.getValue() / runs);
				}
				steps.add(new Step(node.key(), runs, perRun));
				int surplus = runs * node.yield() - owed;
				if (surplus > 0) pool.merge(item, surplus, Integer::sum);
			} finally {
				visiting.remove(item);
			}
		}

		/** Spend what the pool already has. The heart of preferring existing stock. */
		private int take(Identifier item, int count) {
			int have = pool.getOrDefault(item, 0);
			int taken = Math.min(have, count);
			if (taken <= 0) return 0;
			if (have - taken <= 0) pool.remove(item);
			else pool.put(item, have - taken);
			return taken;
		}

		/**
		 * The first recipe for this item that is not already being crafted further up the path.
		 * Without that check a two-way pair such as block and ingot picks itself back. When every
		 * candidate is cyclic there is no usable recipe at all, so the item is reported missing
		 * rather than given a step that could never run.
		 */
		@Nullable
		private RecipeGraph.Node pick(Identifier item) {
			for (RecipeGraph.Node node : RecipeGraph.producing(server, item)) {
				boolean cyclic = false;
				for (RecipeGraph.Choice cell : node.cells()) {
					if (cell.options().stream().anyMatch(visiting::contains)) cyclic = true;
				}
				if (!cyclic) return node;
			}
			return null;
		}
	}

	/**
	 * What the given number of runs of this recipe consumes, with one option chosen per cell:
	 * whatever the stock already holds, or the recipe's own first choice when it holds none.
	 */
	private static Map<Identifier, Integer> needs(RecipeGraph.Node node, int runs, Map<Identifier, Integer> stock) {
		Map<Identifier, Integer> needs = new LinkedHashMap<>();
		for (RecipeGraph.Choice cell : node.cells()) {
			Identifier chosen = null;
			for (Identifier option : cell.options()) {
				// what is already needed from this same run counts against the stock too, or a
				// two-plank recipe reads one plank in stock as enough for both cells
				if (stock.getOrDefault(option, 0) >= needs.getOrDefault(option, 0) + runs) {
					chosen = option;
					break;
				}
			}
			if (chosen == null) chosen = cell.options().get(0);
			needs.merge(chosen, runs, Integer::sum);
		}
		return needs;
	}
}
