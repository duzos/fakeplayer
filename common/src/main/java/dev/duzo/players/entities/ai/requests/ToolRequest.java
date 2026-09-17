package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.RaiseResult;
import dev.duzo.players.api.requests.RaisedRequest;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.JobHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The on-demand half of the request models: a fake that is blocked for want of a tool asks for the
 * best one any reachable storeroom actually holds.
 *
 * <p>Distinct from {@link StockList}, which is a standing list the owner configures. This fires
 * only when a job cannot work at all, which is why it picks from what exists rather than from a
 * quantity the owner named: there is no useful default for "a pickaxe", and asking for a specific
 * tier would leave a fake idle next to a storeroom full of a better one.
 *
 * <p>Candidates are judged on real stacks, so a nearly broken tool is passed over. The request
 * itself can only name an item, so the Runner may still bring a different stack of that item: the
 * ranking picks the best <b>kind</b> present, not one particular tool.
 */
@ApiStatus.Internal
public final class ToolRequest {
	private ToolRequest() {}

	/**
	 * Ask for the best tool a reachable pool holds.
	 *
	 * @param usable which stacks count at all, durability included
	 * @param rank   higher is better, compared across the kinds on offer
	 * @param kind   alert key, so one missing tool is reported once rather than every check
	 * @param what   what to call the tool in the message the owner gets
	 * @return true when a request is now on a board for it
	 */
	public static boolean raiseBest(ServerLevel level, FakePlayerEntity fake,
	                                Predicate<ItemStack> usable, ToDoubleFunction<ItemStack> rank,
	                                String kind, String what) {
		UUID owner = fake.getAIState().ownerUUID();
		if (owner == null) return false;

		Identifier best = null;
		double bestRank = Double.NEGATIVE_INFINITY;
		for (FakePlayerEntity qm : FakePlayerRequests.quartermasters(level, fake, owner)) {
			if (!(qm.level() instanceof ServerLevel qmLevel)) continue;
			for (BlockPos pos : FakePlayerRequests.poolOf(qm)) {
				Container container = JobHelpers.containerAt(qmLevel, pos);
				if (container == null) continue;
				for (int slot = 0; slot < container.getContainerSize(); slot++) {
					ItemStack stack = container.getItem(slot);
					if (stack.isEmpty() || !usable.test(stack)) continue;
					double score = rank.applyAsDouble(stack);
					if (score <= bestRank) continue;
					bestRank = score;
					best = BuiltInRegistries.ITEM.getKey(stack.getItem());
				}
			}
		}

		if (best == null) {
			// nothing anywhere is worth asking for, so say so rather than raise a request that can
			// only ever shortfall. Deduped, because a blocked job re-checks on a short timer.
			SenderAlerts.alert(level, fake, kind, "has no " + what + " and cannot find one in any storeroom");
			return false;
		}

		ItemStack want = new ItemStack(BuiltInRegistries.ITEM.getValue(best));
		RaisedRequest raised = FakePlayerRequests.raise(fake, want, FakePlayerRequests.PRIORITY_FAKE);
		if (raised.result().accepted()) {
			SenderAlerts.clear(fake, kind);
			return true;
		}
		if (raised.result() == RaiseResult.NO_QUARTERMASTER) {
			SenderAlerts.alert(level, fake, kind, "has no " + what + " and no quartermaster in range");
		}
		return false;
	}

	/**
	 * Ranking for a tool with no speed to compare, such as a hoe or a fishing rod: prefer the one
	 * with the most life left, and treat an unbreakable one as better than any that can wear out.
	 */
	public static double byDurability(ItemStack stack) {
		if (!stack.isDamageableItem()) return Double.MAX_VALUE;
		return stack.getMaxDamage() - stack.getDamageValue();
	}

	/** The best usable stack a fake is already carrying, or null. */
	@Nullable
	public static ItemStack bestHeld(FakePlayerEntity fake, Predicate<ItemStack> usable,
	                                 ToDoubleFunction<ItemStack> rank) {
		ItemStack best = null;
		double bestRank = Double.NEGATIVE_INFINITY;
		var inv = fake.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || !usable.test(stack)) continue;
			double score = rank.applyAsDouble(stack);
			if (score > bestRank) {
				bestRank = score;
				best = stack;
			}
		}
		return best;
	}
}
