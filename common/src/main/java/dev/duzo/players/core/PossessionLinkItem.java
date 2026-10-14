package dev.duzo.players.core;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.possession.PossessionManager;
import dev.duzo.players.entities.possession.PossessionReturn;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Right-click one of your fakes to bind it, then use it in the air to take control of that fake from anywhere it is loaded.
 * Costs one durability when the possession ends. Its custom data also holds the return record of a possession in progress.
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
		CompoundTag tag = read(stack);
		if (tag == null) return null;
		return tag.getIntArray(TAG_FAKE).filter(arr -> arr.length == 4).map(UUIDUtil::uuidFromIntArray).orElse(null);
	}

	public static void bind(ItemStack stack, FakePlayerEntity fake) {
		CompoundTag tag = read(stack);
		if (tag == null) tag = new CompoundTag();
		tag.putIntArray(TAG_FAKE, UUIDUtil.uuidToIntArray(fake.getUUID()));
		tag.putString(TAG_NAME, fake.getSkinData().name());
		write(stack, tag);
	}

	@Nullable
	public static PossessionReturn readReturn(ItemStack stack) {
		CompoundTag tag = read(stack);
		return tag == null ? null : PossessionReturn.fromTag(tag.getCompoundOrEmpty(TAG_RETURN));
	}

	public static void writeReturn(ItemStack stack, PossessionReturn record) {
		CompoundTag tag = read(stack);
		if (tag == null) tag = new CompoundTag();
		tag.put(TAG_RETURN, record.toTag());
		write(stack, tag);
	}

	public static void clearReturn(ItemStack stack) {
		CompoundTag tag = read(stack);
		if (tag == null || !tag.contains(TAG_RETURN)) return;
		tag.remove(TAG_RETURN);
		write(stack, tag);
	}

	@Nullable
	private static CompoundTag read(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? null : data.copyTag();
	}

	private static void write(ItemStack stack, CompoundTag tag) {
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!level.isClientSide() && player instanceof ServerPlayer sp) {
			PossessionManager.tryStart(sp, player.getItemInHand(hand));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		CompoundTag tag = read(stack);
		String name = tag == null ? "" : tag.getStringOr(TAG_NAME, "");
		if (boundFake(stack) == null) {
			tooltip.accept(Component.translatable("item.players.possession_link.tooltip.unbound").withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.accept(Component.translatable("item.players.possession_link.tooltip.bound", name).withStyle(ChatFormatting.AQUA));
			tooltip.accept(Component.translatable("item.players.possession_link.tooltip.use").withStyle(ChatFormatting.GRAY));
		}
		tooltip.accept(Component.translatable("item.players.possession_link.tooltip.sneak").withStyle(ChatFormatting.GRAY));
	}
}
