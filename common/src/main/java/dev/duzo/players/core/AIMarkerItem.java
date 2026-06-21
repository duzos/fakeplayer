package dev.duzo.players.core;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public class AIMarkerItem extends Item {
	public static final byte PURPOSE_WAYPOINT = 0;
	public static final byte PURPOSE_REGION = 1;
	public static final byte PURPOSE_CHEST_PICKER = 2;

	public static final byte CHEST_SLOT_DEPOSIT = 0;
	public static final byte CHEST_SLOT_SOURCE = 1;

	public static final long SESSION_TICKS = 90L * 20L;
	public static final double SESSION_RANGE = 32.0D;
	public static final double SESSION_RANGE_SQ = SESSION_RANGE * SESSION_RANGE;

	private static final String TAG_FAKE = "FakePlayerUUID";
	private static final String TAG_OWNER = "OwnerUUID";
	private static final String TAG_PURPOSE = "Purpose";
	private static final String TAG_EXPIRES = "ExpiresAt";
	private static final String TAG_REGION_A = "RegionA";
	private static final String TAG_CHEST_SLOT = "ChestSlot";

	public AIMarkerItem(Properties properties) {
		super(properties.stacksTo(1));
	}

	public static ItemStack make(FakePlayerEntity entity, Player owner, byte purpose, long now) {
		return make(entity, owner, purpose, CHEST_SLOT_DEPOSIT, now);
	}

	public static ItemStack make(FakePlayerEntity entity, Player owner, byte purpose, byte chestSlot, long now) {
		ItemStack stack = new ItemStack(FPItems.AI_MARKER.get());
		CompoundTag tag = stack.getOrCreateTag();
		tag.putUUID(TAG_FAKE, entity.getUUID());
		tag.putUUID(TAG_OWNER, owner.getUUID());
		tag.putString(TAG_PURPOSE, purposeName(purpose));
		tag.putLong(TAG_EXPIRES, now + SESSION_TICKS);
		if (purpose == PURPOSE_CHEST_PICKER) tag.putByte(TAG_CHEST_SLOT, chestSlot);
		stack.setHoverName(Component.literal(purposeLabel(purpose, chestSlot)).withStyle(ChatFormatting.AQUA));
		return stack;
	}

	public static byte chestSlotOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag == null ? CHEST_SLOT_DEPOSIT : tag.getByte(TAG_CHEST_SLOT);
	}

	public static boolean isSession(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof AIMarkerItem && stack.getTag() != null
				&& stack.getTag().contains(TAG_FAKE) && stack.getTag().contains(TAG_OWNER);
	}

	@Nullable
	public static UUID fakeUUID(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.hasUUID(TAG_FAKE) ? tag.getUUID(TAG_FAKE) : null;
	}

	@Nullable
	public static UUID ownerUUID(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
	}

	public static long expiresAt(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag == null ? 0L : tag.getLong(TAG_EXPIRES);
	}

	public static void bumpExpiry(ItemStack stack, long now) {
		CompoundTag tag = stack.getTag();
		if (tag == null) return;
		tag.putLong(TAG_EXPIRES, now + SESSION_TICKS);
	}

	private static String purposeName(byte purpose) {
		return switch (purpose) {
			case PURPOSE_WAYPOINT -> "WAYPOINT";
			case PURPOSE_REGION -> "REGION";
			case PURPOSE_CHEST_PICKER -> "CHEST_PICKER";
			default -> "UNKNOWN";
		};
	}

	public static byte purposeOf(ItemStack stack) {
		return purposeFromTag(stack.getTag());
	}

	@Nullable
	public static BlockPos regionA(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains(TAG_REGION_A)) return null;
		return BlockPos.of(tag.getLong(TAG_REGION_A));
	}

	private static byte purposeFromTag(@Nullable CompoundTag tag) {
		if (tag == null) return -1;
		return switch (tag.getString(TAG_PURPOSE)) {
			case "WAYPOINT" -> PURPOSE_WAYPOINT;
			case "REGION" -> PURPOSE_REGION;
			case "CHEST_PICKER" -> PURPOSE_CHEST_PICKER;
			default -> -1;
		};
	}

	private static String purposeLabel(byte purpose, byte chestSlot) {
		return switch (purpose) {
			case PURPOSE_WAYPOINT -> "Waypoint Marker";
			case PURPOSE_REGION -> "Region Marker";
			case PURPOSE_CHEST_PICKER -> chestSlot == CHEST_SLOT_SOURCE ? "Source Marker" : "Deposit Marker";
			default -> "AI Marker";
		};
	}

	@Override
	public InteractionResult useOn(UseOnContext ctx) {
		if (ctx.getLevel().isClientSide()) return InteractionResult.SUCCESS;
		Player player = ctx.getPlayer();
		if (player == null) return InteractionResult.PASS;

		ItemStack stack = ctx.getItemInHand();
		CompoundTag tag = stack.getTag();
		byte purpose = purposeFromTag(tag);
		if (tag == null || purpose < 0 || !tag.hasUUID(TAG_FAKE)) {
			silentlyConsume(stack);
			return InteractionResult.FAIL;
		}

		ServerLevel level = (ServerLevel) ctx.getLevel();
		Entity raw = level.getEntity(tag.getUUID(TAG_FAKE));
		if (!(raw instanceof FakePlayerEntity entity)) {
			silentlyConsume(stack);
			return InteractionResult.FAIL;
		}

		BlockPos pos = ctx.getClickedPos();

		switch (purpose) {
			case PURPOSE_WAYPOINT -> {
				entity.mutateAIState(s -> s.setWaypoint(pos.immutable()));
				player.displayClientMessage(Component.literal("Waypoint set.").withStyle(ChatFormatting.GREEN), true);
				silentlyConsume(stack);
			}
			case PURPOSE_REGION -> {
				if (!tag.contains(TAG_REGION_A)) {
					tag.putLong(TAG_REGION_A, pos.asLong());
					bumpExpiry(stack, level.getGameTime());
					player.displayClientMessage(Component.literal("Region corner A set, click another block for B.").withStyle(ChatFormatting.YELLOW), true);
				} else {
					BlockPos a = BlockPos.of(tag.getLong(TAG_REGION_A));
					BlockPos b = pos.immutable();
					entity.mutateAIState(s -> {
						s.setRegionA(a);
						s.setRegionB(b);
					});
					player.displayClientMessage(Component.literal("Region set.").withStyle(ChatFormatting.GREEN), true);
					silentlyConsume(stack);
				}
			}
			case PURPOSE_CHEST_PICKER -> {
				if (!isValidContainer(level, pos)) {
					player.displayClientMessage(Component.literal("Right-click a container.").withStyle(ChatFormatting.RED), true);
					return InteractionResult.FAIL;
				}
				byte slot = tag.getByte(TAG_CHEST_SLOT);
				BlockPos commit = pos.immutable();
				if (slot == CHEST_SLOT_SOURCE) {
					entity.mutateAIState(s -> s.setSourceChest(commit));
					player.displayClientMessage(Component.literal("Source chest set.").withStyle(ChatFormatting.GREEN), true);
				} else {
					entity.mutateAIState(s -> s.setDepositChest(commit));
					player.displayClientMessage(Component.literal("Deposit chest set.").withStyle(ChatFormatting.GREEN), true);
				}
				silentlyConsume(stack);
			}
		}

		return InteractionResult.CONSUME;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.isShiftKeyDown() && !level.isClientSide() && player instanceof ServerPlayer) {
			silentlyConsume(stack);
			return InteractionResultHolder.consume(stack);
		}
		return InteractionResultHolder.pass(stack);
	}

	private static void silentlyConsume(ItemStack stack) {
		stack.setCount(0);
	}

	public static boolean isValidContainer(Level level, BlockPos pos) {
		return HopperBlockEntity.getContainerAt(level, pos) != null;
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		CompoundTag tag = stack.getTag();
		if (tag == null) return;
		byte purpose = purposeFromTag(tag);
		String hint = switch (purpose) {
			case PURPOSE_WAYPOINT -> "Right-click a block to mark a waypoint.";
			case PURPOSE_REGION -> tag.contains(TAG_REGION_A)
					? "Right-click a second block for corner B."
					: "Right-click a block for corner A.";
			case PURPOSE_CHEST_PICKER -> tag.getByte(TAG_CHEST_SLOT) == CHEST_SLOT_SOURCE
					? "Right-click a chest to set source target."
					: "Right-click a chest to set deposit target.";
			default -> "";
		};
		if (!hint.isEmpty()) {
			tooltip.add(Component.literal(hint).withStyle(ChatFormatting.GRAY));
			tooltip.add(Component.literal("Sneak + right-click air to cancel.").withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return true;
	}

	public static void clearAllFor(Player player) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (isSession(s)) inv.setItem(i, ItemStack.EMPTY);
		}
	}
}
