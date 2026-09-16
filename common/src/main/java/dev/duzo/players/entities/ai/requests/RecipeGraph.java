package dev.duzo.players.entities.ai.requests;

import dev.duzo.players.Constants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's crafting recipes, indexed the way a planner needs them: by what they produce.
 *
 * <p>Every version-sensitive piece of recipe access lives here, so a port has one file to re-spell
 * rather than a planner full of them. Recipes are keyed by a plain identifier rather than by a
 * {@code ResourceKey}, because the recipe registry key does not exist on every branch this mod
 * ships to.
 */
@ApiStatus.Internal
public final class RecipeGraph {
	/** The items that satisfy one occupied cell of a grid, in the order the recipe offers them. */
	public record Choice(List<ResourceLocation> options) {}

	/** One recipe, reduced to what it consumes per craft and what it yields. */
	public record Node(ResourceLocation key, ResourceLocation output, ItemStack result, List<Choice> cells) {
		public int yield() {
			return result.getCount();
		}
	}

	private static final Map<ResourceLocation, List<Node>> BY_OUTPUT = new HashMap<>();
	private static final Map<ResourceLocation, Node> BY_KEY = new HashMap<>();
	// weak, or a quit to title leaves the closed world's whole recipe set, and the registries
	// behind it, reachable from a static field. PoolIndex avoids the same trap with UUIDs.
	private static WeakReference<Object> builtFrom = new WeakReference<>(null);
	private static int builtCount = -1;

	private RecipeGraph() {}

	/** Recipes producing this item, in recipe order. */
	public static List<Node> producing(MinecraftServer server, ResourceLocation item) {
		ensure(server);
		return BY_OUTPUT.getOrDefault(item, List.of());
	}

	@Nullable
	public static Node byKey(MinecraftServer server, ResourceLocation key) {
		ensure(server);
		return BY_KEY.get(key);
	}

	/**
	 * Rebuilt when the recipe manager is replaced or its recipe count changes, which is what a
	 * datapack reload does. Cheaper than listening for the reload on two loaders, and a stale index
	 * costs a failed plan rather than a wrong craft, because every step is re-read before it runs.
	 */
	private static void ensure(MinecraftServer server) {
		Object manager = server.getRecipeManager();
		int count = server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).size();
		if (manager == builtFrom.get() && count == builtCount) return;
		build(server, count);
		builtFrom = new WeakReference<>(manager);
		builtCount = count;
	}

	private static void build(MinecraftServer server, int count) {
		BY_OUTPUT.clear();
		BY_KEY.clear();
		for (RecipeHolder<CraftingRecipe> holder : server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
			CraftingRecipe recipe = holder.value();
			// a special recipe builds its result out of its inputs, so it has no fixed output to
			// index and no cost that a plan could work out in advance
			if (recipe.isSpecial()) continue;
			Node node = read(server, holder.id(), recipe);
			if (node == null) continue;
			BY_OUTPUT.computeIfAbsent(node.output(), k -> new ArrayList<>()).add(node);
			BY_KEY.put(node.key(), node);
		}
		Constants.debug("[fpdebug] RECIPE-GRAPH indexed {} outputs from {} recipes", BY_OUTPUT.size(), count);
	}

	@Nullable
	private static Node read(MinecraftServer server, ResourceLocation key, CraftingRecipe recipe) {
		ItemStack result;
		try {
			result = recipe.getResultItem(server.registryAccess());
		} catch (Throwable t) {
			// Throwable, not Exception: a mod compiled against another version throws NoSuchMethodError
			// here, and one bad recipe must not take the tick down with it
			return null;
		}
		if (result.isEmpty()) return null;

		List<Choice> cells = new ArrayList<>();
		for (Ingredient ingredient : recipe.getIngredients()) {
			if (ingredient.isEmpty()) continue;
			List<ResourceLocation> options = optionsOf(ingredient);
			if (options.isEmpty()) return null;
			cells.add(new Choice(options));
		}
		if (cells.isEmpty() || cells.size() > 9) return null;
		return new Node(key, BuiltInRegistries.ITEM.getKey(result.getItem()), result.copy(), List.copyOf(cells));
	}

	/**
	 * Every item this ingredient accepts, minus any that leaves a remainder behind when crafted.
	 * A bucket or a bottle comes back out of the grid, and nothing here models that, so a recipe
	 * that would destroy one is dropped from the graph instead.
	 */
	private static List<ResourceLocation> optionsOf(Ingredient ingredient) {
		List<ResourceLocation> options = new ArrayList<>();
		for (ItemStack stack : ingredient.getItems()) {
			Item item = stack.getItem();
			if (item.hasCraftingRemainingItem()) continue;
			ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
			if (!options.contains(id)) options.add(id);
		}
		return options;
	}
}
