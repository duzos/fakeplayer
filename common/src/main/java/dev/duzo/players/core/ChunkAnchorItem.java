package dev.duzo.players.core;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class ChunkAnchorItem extends Item {
	public ChunkAnchorItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		for (int i = 1; i <= 3; i++) {
			tooltip.add(Component.translatable("item.players.chunk_anchor.tooltip." + i).withStyle(ChatFormatting.GRAY));
		}
	}
}
