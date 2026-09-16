package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.RaiseResult;
import dev.duzo.players.api.requests.RaisedRequest;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Keeps a fake topped up against its {@link StockList} without ever blocking it.
 *
 * <p>Generalised from the Fisherman, which self-requested a rod on a cooldown of its own: a fake
 * raises for whatever it is short of and carries on working, so a request that cannot be filled
 * costs nothing but a message.
 *
 * <p>Ticked centrally for any job whose {@code JobRow.STOCK} says it keeps stock, so an addon job
 * gets this by declaring the row and writes no code for it.
 */
@ApiStatus.Internal
public final class StockKeeper {
	private static final String ALERT_NO_QM = "stock-noqm";
	private static final String ALERT_FULL = "stock-boardfull";

	private StockKeeper() {}

	/** Ticks between checks, and the initial stagger, so a base of fakes does not all check at once. */
	public static int checkTicks() {
		return 20 * Math.max(1, PlayersConfig.get().requestStockCheckSeconds);
	}

	/**
	 * Called once the entity's own countdown has elapsed. A countdown rather than a modulo on the
	 * game time: a fake whose chunk stops ticking, or which is paused because its owner has the
	 * menu open, would miss a fixed tick phase and skip whole periods at a time.
	 */
	public static void tick(ServerLevel level, FakePlayerEntity fake) {
		// the cheap string read first: read() parses and validates every entry, and paying that on
		// a fake with no list at all is the per-tick cost the caller's cached flag exists to avoid
		if (StockList.isEmpty(fake.getAIState())) return;
		List<StockList.Entry> wanted = StockList.read(fake.getAIState());
		if (wanted.isEmpty()) return;

		boolean anyNoQuartermaster = false;
		boolean anyBoardFull = false;
		String noQuartermaster = "";
		String boardFull = "";

		for (StockList.Entry entry : wanted) {
			int owed = entry.target() - held(fake, entry.item());
			if (owed <= 0) continue;
			Item item = BuiltInRegistries.ITEM.getOptional(entry.item()).orElse(null);
			if (item == null) continue;

			RaisedRequest raised = FakePlayerRequests.raise(fake, new ItemStack(item, owed),
					FakePlayerRequests.PRIORITY_FAKE);
			if (raised.result() == RaiseResult.NO_QUARTERMASTER) {
				anyNoQuartermaster = true;
				noQuartermaster = "wants " + owed + " " + entry.item() + " and has no quartermaster in range";
			} else if (raised.result() == RaiseResult.BOARD_FULL) {
				anyBoardFull = true;
				boardFull = "wants " + owed + " " + entry.item() + " but its quartermaster's board is full";
			}
		}

		// decided across the whole pass, not per entry: clearing inside the loop let one entry that
		// succeeded wipe the cooldown another entry had just set, and the alert fired every check
		if (anyNoQuartermaster) SenderAlerts.alert(level, fake, ALERT_NO_QM, noQuartermaster);
		else SenderAlerts.clear(fake, ALERT_NO_QM);
		if (anyBoardFull) SenderAlerts.alert(level, fake, ALERT_FULL, boardFull);
		else SenderAlerts.clear(fake, ALERT_FULL);
	}

	/**
	 * Counts the hands and the armour as well as the inventory. A tool a fake is using sits in a
	 * hand slot and a helmet it picked up sits on its head, and counting only the inventory would
	 * ask for a second one of everything it is already wearing or holding.
	 */
	private static int held(FakePlayerEntity fake, Identifier item) {
		int n = Haul.countOf(fake, item);
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			n += matching(fake.getItemBySlot(slot), item);
		}
		return n;
	}

	private static int matching(ItemStack stack, Identifier item) {
		if (stack.isEmpty()) return 0;
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item) ? stack.getCount() : 0;
	}
}
