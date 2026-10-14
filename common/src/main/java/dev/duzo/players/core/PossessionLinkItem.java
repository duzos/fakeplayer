package dev.duzo.players.core;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.possession.PossessionManager;
import dev.duzo.players.entities.possession.PossessionReturn;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Right-click one of your fakes to bind it, then use it in the air to take control of that fake from anywhere it is loaded.
 * Costs one durability when the possession ends. Its tag also holds the return record of a possession in progress.
 */
public class PossessionLinkItem extends Item {
	public static final int DURABILITY = 64;

	private static final String TAG_FAKE = "Fake";
	private static final String TAG_NAME = "FakeName";
	private static final String TAG_RETURN = "Return";

	public PossessionLinkItem(Properties properties) {
		super(properties);
	}

	@Nullable
	public static UUID boundFake(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null) return null;
		int[] arr = tag.getIntArray(TAG_FAKE);
		return arr.length == 4 ? UUIDUtil.uuidFromIntArray(arr) : null;
	}

	public static void bind(ItemStack stack, FakePlayerEntity fake) {
		CompoundTag tag = stack.getOrCreateTag();
		tag.putIntArray(TAG_FAKE, UUIDUtil.uuidToIntArray(fake.getUUID()));
		tag.putString(TAG_NAME, fake.getSkinData().name());
	}

	@Nullable
	public static PossessionReturn readReturn(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag == null || !tag.contains(TAG_RETURN) ? null : PossessionReturn.fromTag(tag.getCompound(TAG_RETURN));
	}

	public static void writeReturn(ItemStack stack, PossessionReturn record) {
		stack.getOrCreateTag().put(TAG_RETURN, record.toTag());
	}

	public static void clearReturn(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag != null) tag.remove(TAG_RETURN);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!level.isClientSide() && player instanceof ServerPlayer sp) {
			PossessionManager.tryStart(sp, stack);
		}
		return InteractionResultHolder.success(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		CompoundTag tag = stack.getTag();
		String name = tag == null ? "" : tag.getString(TAG_NAME);
		if (boundFake(stack) == null) {
			tooltip.add(Component.translatable("item.players.possession_link.tooltip.unbound").withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(Component.translatable("item.players.possession_link.tooltip.bound", name).withStyle(ChatFormatting.AQUA));
			tooltip.add(Component.translatable("item.players.possession_link.tooltip.use").withStyle(ChatFormatting.GRAY));
		}
		tooltip.add(Component.translatable("item.players.possession_link.tooltip.sneak").withStyle(ChatFormatting.GRAY));
	}
}
