package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.AIState;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A Crafter's orders: which Quartermaster asked, which recipes to run, with what, and in what order.
 *
 * <p>The counterpart to {@link Haul}. It lives in the Crafter's own AIState for the same reason:
 * presence of a Commission is what "this Crafter is busy" means, and a Quartermaster must be able
 * to see that without the Crafter having ticked.
 *
 * <p>Each step carries the ingredients the planner chose, rather than a recipe id alone. A grid
 * cell that accepts a tag has to resolve to the same plank at plan time and at collect time, and
 * the pool has moved on in between.
 */
@ApiStatus.Internal
public record Commission(UUID quartermaster, List<Entry> steps, int cursor, Identifier goal,
                         int goalCount, long since, boolean cancelled) {
	/** One planned step: a recipe, how many times to run it, and what one run spends. */
	public record Entry(Identifier recipe, int times, Map<Identifier, Integer> perRun) {}

	private static final String TAG = "Commission";

	/**
	 * Whether orders are set at all, parseable or not. {@link #of} returns null for orders it
	 * cannot read, and treating that as "free" would hand a second commission to a Crafter that is
	 * still carrying the first one's goods.
	 */
	public static boolean isPresent(AIState state) {
		return !state.jobParams().getCompoundOrEmpty(TAG).isEmpty();
	}

	@Nullable
	public static Commission of(AIState state) {
		CompoundTag tag = state.jobParams().getCompoundOrEmpty(TAG);
		if (tag.isEmpty()) return null;
		int[] raw = tag.getIntArray("Qm").orElse(null);
		if (raw == null || raw.length != 4) return null;
		Identifier goal = Identifier.tryParse(tag.getStringOr("Goal", ""));
		if (goal == null) return null;
		ListTag list = tag.getListOrEmpty("Steps");
		List<Entry> steps = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag step = list.getCompoundOrEmpty(i);
			Identifier recipe = Identifier.tryParse(step.getStringOr("Id", ""));
			Map<Identifier, Integer> perRun = readNeeds(step.getStringOr("Needs", ""));
			if (recipe == null || perRun.isEmpty()) return null;
			steps.add(new Entry(recipe, Math.max(1, step.getIntOr("Times", 1)), perRun));
		}
		if (steps.isEmpty()) return null;
		return new Commission(UUIDUtil.uuidFromIntArray(raw), List.copyOf(steps),
				Math.max(0, tag.getIntOr("Cursor", 0)), goal,
				Math.max(0, tag.getIntOr("GoalCount", 0)), tag.getLongOr("Since", 0L),
				tag.getBooleanOr("Cancelled", false));
	}

	public static boolean isBusy(FakePlayerEntity crafter) {
		return isPresent(crafter.getAIState());
	}

	/** The step being run now, or null once every step is done. */
	@Nullable
	public Entry current() {
		return cursor < steps.size() ? steps.get(cursor) : null;
	}

	public boolean finished() {
		return cursor >= steps.size();
	}

	/** Every item any step of this commission touches, which is what belongs back in the pool. */
	public java.util.Set<Identifier> itemsInvolved() {
		java.util.Set<Identifier> items = new java.util.HashSet<>();
		items.add(goal);
		for (Entry step : steps) items.addAll(step.perRun().keySet());
		return items;
	}

	/**
	 * @return false when the orders could not be stored, in which case the Crafter is NOT marked
	 *         busy and must not be commissioned.
	 */
	public static boolean write(FakePlayerEntity crafter, Commission commission) {
		return crafter.mutateAIState(state -> {
			CompoundTag tag = new CompoundTag();
			tag.putIntArray("Qm", UUIDUtil.uuidToIntArray(commission.quartermaster()));
			tag.putString("Goal", commission.goal().toString());
			tag.putInt("GoalCount", commission.goalCount());
			tag.putInt("Cursor", commission.cursor());
			tag.putLong("Since", commission.since());
			if (commission.cancelled()) tag.putBoolean("Cancelled", true);
			ListTag list = new ListTag();
			for (Entry step : commission.steps()) {
				CompoundTag entry = new CompoundTag();
				entry.putString("Id", step.recipe().toString());
				entry.putInt("Times", step.times());
				entry.putString("Needs", writeNeeds(step.perRun()));
				list.add(entry);
			}
			tag.put("Steps", list);
			CompoundTag params = state.jobParams();
			params.put(TAG, tag);
			state.setJobParams(params);
		});
	}

	/**
	 * Advance to the next step, or clear the orders once there is none.
	 *
	 * @return false when nothing was stored, in which case the cursor has NOT moved and the caller
	 *         must not treat the step as done: re-running it drains the pool a step at a time.
	 */
	public static boolean advance(FakePlayerEntity crafter, Commission commission) {
		Commission next = new Commission(commission.quartermaster(), commission.steps(),
				commission.cursor() + 1, commission.goal(), commission.goalCount(),
				commission.since(), commission.cancelled());
		return next.finished() ? clear(crafter) : write(crafter, next);
	}

	/**
	 * Tell the Crafter to give these orders up. Deliberately not a clear: the Crafter is holding
	 * goods the storeroom paid for, and only the Crafter can walk them back. Clearing from outside
	 * frees it still carrying them, and its standing recipe then banks them in its own chest.
	 */
	public static boolean cancel(FakePlayerEntity crafter, Commission commission) {
		if (commission.cancelled()) return true;
		return write(crafter, new Commission(commission.quartermaster(), commission.steps(),
				commission.cursor(), commission.goal(), commission.goalCount(),
				commission.since(), true));
	}

	/** @return false when the orders could not be removed, leaving the Crafter marked busy. */
	public static boolean clear(FakePlayerEntity crafter) {
		return crafter.mutateAIState(state -> {
			CompoundTag params = state.jobParams();
			params.remove(TAG);
			state.setJobParams(params);
		});
	}

	// stored as one string per step rather than a compound per ingredient: AIState is synced as
	// SNBT under a hard character cap, and a 16-step commission of compounds is several times this
	private static String writeNeeds(Map<Identifier, Integer> perRun) {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<Identifier, Integer> need : perRun.entrySet()) {
			if (!out.isEmpty()) out.append(',');
			out.append(need.getKey()).append(' ').append(need.getValue());
		}
		return out.toString();
	}

	private static Map<Identifier, Integer> readNeeds(String raw) {
		Map<Identifier, Integer> perRun = new LinkedHashMap<>();
		if (raw == null || raw.isBlank()) return perRun;
		for (String part : raw.split(",")) {
			int split = part.lastIndexOf(' ');
			if (split <= 0) return Map.of();
			Identifier item = Identifier.tryParse(part.substring(0, split).trim());
			if (item == null) return Map.of();
			try {
				int count = Integer.parseInt(part.substring(split + 1).trim());
				if (count <= 0) return Map.of();
				perRun.put(item, count);
			} catch (NumberFormatException e) {
				return Map.of();
			}
		}
		return perRun;
	}
}
