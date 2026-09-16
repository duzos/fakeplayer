package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.entities.ai.AIState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * A fake's keep stocked list: items it wants to hold a standing quantity of.
 *
 * <p>Stored as one string in jobParams rather than as a list of compounds, because AIState is
 * synced as SNBT under a hard character cap and a compound per entry costs several times the text.
 * The wire and storage form is {@code item count,item count}, which is also what the AI menu shows,
 * so there is nothing to translate between the box and the save.
 */
@ApiStatus.Internal
public final class StockList {
	/** One line of the list. */
	public record Entry(Identifier item, int target) {}

	private static final String TAG_STOCK = "Stock";
	/** Bounded so one fake cannot raise an unbounded number of requests per check. */
	public static final int MAX_ENTRIES = 8;

	private StockList() {}

	public static String text(AIState state) {
		return state.jobParams().getStringOr(TAG_STOCK, "");
	}

	public static List<Entry> read(AIState state) {
		return parse(text(state));
	}

	public static boolean isEmpty(AIState state) {
		return text(state).isEmpty();
	}

	/** Stores the normalized form, or clears the list when nothing in it parsed. */
	public static void write(AIState state, String raw) {
		String normalized = normalize(raw);
		CompoundTag params = state.jobParams();
		if (normalized.isEmpty()) params.remove(TAG_STOCK);
		else params.putString(TAG_STOCK, normalized);
		state.setJobParams(params);
	}

	/**
	 * Drops anything unparseable rather than rejecting the whole line, so one typo does not throw
	 * away a list the player has built up. A missing count means one.
	 */
	public static String normalize(String raw) {
		StringBuilder out = new StringBuilder();
		int kept = 0;
		for (Entry entry : parse(raw)) {
			if (kept >= MAX_ENTRIES) break;
			if (!out.isEmpty()) out.append(',');
			out.append(entry.item()).append(' ').append(entry.target());
			kept++;
		}
		return out.toString();
	}

	private static List<Entry> parse(String raw) {
		List<Entry> out = new ArrayList<>();
		if (raw == null || raw.isBlank()) return out;
		for (String part : raw.split(",")) {
			Entry entry = parseEntry(part);
			if (entry == null) continue;
			// a repeated item would raise the same key twice a pass, and the second raise would
			// merely top the first one up, so the later line is silently dead. Keep the first.
			if (out.stream().anyMatch(e -> e.item().equals(entry.item()))) continue;
			out.add(entry);
			if (out.size() >= MAX_ENTRIES) break;
		}
		return out;
	}

	private static Entry parseEntry(String part) {
		String token = part.trim();
		if (token.isEmpty()) return null;
		String name = token;
		int target = 1;
		int split = token.lastIndexOf(' ');
		if (split > 0) {
			name = token.substring(0, split).trim();
			String count = token.substring(split + 1).trim();
			// an "x64" suffix is the natural way to write it and costs one character to accept
			if (count.startsWith("x") || count.startsWith("X")) count = count.substring(1);
			try {
				target = Integer.parseInt(count);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		Identifier item = Identifier.tryParse(name);
		if (item == null) return null;
		// an unknown id is dropped here rather than kept for later: nothing can ever stock it, and
		// keeping it would raise a request every check for an item no registry can name
		if (!BuiltInRegistries.ITEM.containsKey(item)) return null;
		return new Entry(item, Math.max(1, Math.min(ItemRequest.MAX_COUNT, target)));
	}
}
