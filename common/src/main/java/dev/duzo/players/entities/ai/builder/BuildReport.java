package dev.duzo.players.entities.ai.builder;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The chat summary of a plan: its size, what it needs, and what it cannot place. */
public final class BuildReport {
	private static final int SHOWN = 10;

	private BuildReport() {}

	public static List<Component> lines(BuildPlan plan) {
		List<Component> out = new ArrayList<>();
		out.add(Component.literal("Build " + plan.bounds.getXSpan() + "x" + plan.bounds.getYSpan() + "x"
				+ plan.bounds.getZSpan() + " at " + plan.bounds.minX() + ", " + plan.bounds.minY() + ", "
				+ plan.bounds.minZ() + ", " + plan.cellCount() + " cells."));
		List<Map.Entry<Item, Integer>> items = new ArrayList<>(plan.bom.entrySet());
		items.sort((a, b) -> b.getValue() - a.getValue());
		StringBuilder sb = new StringBuilder("Needs: ");
		for (int i = 0; i < Math.min(SHOWN, items.size()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(items.get(i).getValue()).append(" ").append(new ItemStack(items.get(i).getKey()).getHoverName().getString());
		}
		if (items.size() > SHOWN) sb.append(", +").append(items.size() - SHOWN).append(" more kinds");
		if (items.isEmpty()) sb.append("nothing");
		out.add(Component.literal(sb.toString()));
		if (!plan.unsupported.isEmpty()) {
			StringBuilder u = new StringBuilder("Cannot place: ");
			int n = 0;
			for (Map.Entry<Block, Integer> e : plan.unsupported.entrySet()) {
				if (n++ > 0) u.append(", ");
				u.append(e.getValue()).append(" ").append(e.getKey().getName().getString());
				if (n >= SHOWN) break;
			}
			out.add(Component.literal(u.toString()));
		}
		return out;
	}
}
