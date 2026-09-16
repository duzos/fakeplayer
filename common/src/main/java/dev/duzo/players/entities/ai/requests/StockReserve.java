package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * How much of what a fake is carrying belongs to its {@link StockList} and must not be deposited.
 *
 * <p>Without this every job with a deposit leg would bank the goods a Runner had just delivered,
 * drop below target again, and ask for more: a pump that empties a storeroom into a chest one
 * delivery at a time. Built fresh per deposit run, because a run is where the budget is spent.
 */
@ApiStatus.Internal
public final class StockReserve {
	private static final StockReserve EMPTY = new StockReserve(Map.of());

	private final Map<Identifier, Integer> budget;

	private StockReserve(Map<Identifier, Integer> budget) {
		this.budget = budget;
	}

	public static StockReserve of(FakePlayerEntity fake) {
		java.util.List<StockList.Entry> wanted = StockList.read(fake.getAIState());
		if (wanted.isEmpty()) return EMPTY;
		Map<Identifier, Integer> budget = new HashMap<>();
		for (StockList.Entry entry : wanted) budget.put(entry.item(), entry.target());
		// a held tool, or worn armour, counts towards its own target, so the inventory only owes
		// the rest. The same slots StockKeeper counts, or the two disagree by whatever is worn.
		for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
			spend(budget, fake.getItemBySlot(slot));
		}
		return new StockReserve(budget);
	}

	private static void spend(Map<Identifier, Integer> budget, ItemStack stack) {
		if (stack.isEmpty()) return;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		Integer left = budget.get(id);
		if (left == null) return;
		int now = left - stack.getCount();
		if (now <= 0) budget.remove(id);
		else budget.put(id, now);
	}

	public boolean isEmpty() {
		return budget.isEmpty();
	}

	/**
	 * How many units of this stack to keep, spending that much of the budget. Call once per slot,
	 * in the order slots are visited, so a target spread over several stacks is met exactly once.
	 */
	public int holdBack(ItemStack stack) {
		if (budget.isEmpty() || stack.isEmpty()) return 0;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		Integer left = budget.get(id);
		if (left == null) return 0;
		int keep = Math.min(left, stack.getCount());
		if (left - keep <= 0) budget.remove(id);
		else budget.put(id, left - keep);
		return keep;
	}
}
