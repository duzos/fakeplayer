package dev.duzo.players.core;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.GuardJobExecutor;
import dev.duzo.players.entities.ai.Job;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class AIMarkerItem extends Item {
	public static final byte PURPOSE_WAYPOINT = 0;
	public static final byte PURPOSE_REGION = 1;
	public static final byte PURPOSE_CHEST_PICKER = 2;

	public static final long SESSION_TICKS = 90L * 20L;
	public static final double SESSION_RANGE = 32.0D;
	public static final double SESSION_RANGE_SQ = SESSION_RANGE * SESSION_RANGE;

	private static final String TAG_FAKE = "FakePlayerUUID";
	private static final String TAG_OWNER = "OwnerUUID";
	private static final String TAG_PURPOSE = "Purpose";
	private static final String TAG_EXPIRES = "ExpiresAt";
	private static final String TAG_REGION_A = "RegionA";

	public AIMarkerItem(Properties properties) {
		super(properties.stacksTo(1));
	}

	public static ItemStack make(FakePlayerEntity entity, Player owner, byte purpose, long now) {
		ItemStack stack = new ItemStack(FPItems.AI_MARKER.get());
		CompoundTag tag = new CompoundTag();
		tag.putIntArray(TAG_FAKE, UUIDUtil.uuidToIntArray(entity.getUUID()));
		tag.putIntArray(TAG_OWNER, UUIDUtil.uuidToIntArray(owner.getUUID()));
		tag.putString(TAG_PURPOSE, purposeName(purpose));
		tag.putLong(TAG_EXPIRES, now + SESSION_TICKS);
		writeTag(stack, tag);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(purposeLabel(purpose)).withStyle(ChatFormatting.AQUA));
		return stack;
	}

	public static boolean isSession(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof AIMarkerItem)) return false;
		CompoundTag tag = readTag(stack);
		return tag != null && tag.contains(TAG_FAKE) && tag.contains(TAG_OWNER);
	}

	@Nullable
	public static UUID fakeUUID(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return tag == null ? null : readUUID(tag, TAG_FAKE);
	}

	@Nullable
	public static UUID ownerUUID(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return tag == null ? null : readUUID(tag, TAG_OWNER);
	}

	public static long expiresAt(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return tag == null ? 0L : tag.getLongOr(TAG_EXPIRES, 0L);
	}

	public static void bumpExpiry(ItemStack stack, long now) {
		CompoundTag tag = readTag(stack);
		if (tag == null) return;
		tag.putLong(TAG_EXPIRES, now + SESSION_TICKS);
		writeTag(stack, tag);
	}

	public static byte purpose(ItemStack stack) {
		return purposeFromTag(readTag(stack));
	}

	@Nullable
	public static BlockPos regionA(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (tag == null || !tag.contains(TAG_REGION_A)) return null;
		return BlockPos.of(tag.getLongOr(TAG_REGION_A, 0L));
	}

	@Nullable
	private static UUID readUUID(CompoundTag tag, String key) {
		return tag.getIntArray(key)
				.filter(arr -> arr.length == 4)
				.map(UUIDUtil::uuidFromIntArray)
				.orElse(null);
	}

	private static String purposeName(byte purpose) {
		return switch (purpose) {
			case PURPOSE_WAYPOINT -> "WAYPOINT";
			case PURPOSE_REGION -> "REGION";
			case PURPOSE_CHEST_PICKER -> "CHEST_PICKER";
			default -> "UNKNOWN";
		};
	}

	private static byte purposeFromTag(@Nullable CompoundTag tag) {
		if (tag == null) return -1;
		return switch (tag.getStringOr(TAG_PURPOSE, "")) {
			case "WAYPOINT" -> PURPOSE_WAYPOINT;
			case "REGION" -> PURPOSE_REGION;
			case "CHEST_PICKER" -> PURPOSE_CHEST_PICKER;
			default -> -1;
		};
	}

	private static String purposeLabel(byte purpose) {
		return switch (purpose) {
			case PURPOSE_WAYPOINT -> "Waypoint Marker";
			case PURPOSE_REGION -> "Region Marker";
			case PURPOSE_CHEST_PICKER -> "Deposit Marker";
			default -> "AI Marker";
		};
	}

	private static CompoundTag readTag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? null : data.copyTag();
	}

	private static void writeTag(ItemStack stack, CompoundTag tag) {
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	@Override
	public InteractionResult useOn(UseOnContext ctx) {
		if (ctx.getLevel().isClientSide()) return InteractionResult.SUCCESS;
		Player player = ctx.getPlayer();
		if (player == null) return InteractionResult.PASS;

		ItemStack stack = ctx.getItemInHand();
		CompoundTag tag = readTag(stack);
		byte purpose = purposeFromTag(tag);
		UUID fake = tag == null ? null : readUUID(tag, TAG_FAKE);
		if (purpose < 0 || fake == null) {
			silentlyConsume(stack);
			return InteractionResult.FAIL;
		}

		ServerLevel level = (ServerLevel) ctx.getLevel();
		Entity raw = level.getEntity(fake);
		if (!(raw instanceof FakePlayerEntity entity)) {
			silentlyConsume(stack);
			return InteractionResult.FAIL;
		}

		BlockPos pos = ctx.getClickedPos();

		switch (purpose) {
			case PURPOSE_WAYPOINT -> {
				boolean guard = entity.getAIState().job() == Job.GUARD;
				entity.mutateAIState(s -> {
					if (s.job() == Job.GUARD) {
						GuardJobExecutor.appendPatrolPoint(s, pos.immutable());
					} else {
						s.setWaypoint(pos.immutable());
					}
				});
				String msg = guard ? "Patrol point added." : "Waypoint set.";
				player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GREEN), true);
				silentlyConsume(stack);
			}
			case PURPOSE_REGION -> {
				if (!tag.contains(TAG_REGION_A)) {
					tag.putLong(TAG_REGION_A, pos.asLong());
					writeTag(stack, tag);
					bumpExpiry(stack, level.getGameTime());
					player.displayClientMessage(Component.literal("Region corner A set, click another block for B.").withStyle(ChatFormatting.YELLOW), true);
				} else {
					BlockPos a = BlockPos.of(tag.getLongOr(TAG_REGION_A, 0L));
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
				entity.mutateAIState(s -> s.setDepositChest(pos.immutable()));
				player.displayClientMessage(Component.literal("Deposit container set.").withStyle(ChatFormatting.GREEN), true);
				silentlyConsume(stack);
			}
		}

		return InteractionResult.CONSUME;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player.isShiftKeyDown() && !level.isClientSide() && player instanceof ServerPlayer) {
			silentlyConsume(player.getItemInHand(hand));
			return InteractionResult.CONSUME;
		}
		return InteractionResult.PASS;
	}

	private static void silentlyConsume(ItemStack stack) {
		stack.setCount(0);
	}

	public static boolean isValidContainer(Level level, BlockPos pos) {
		return HopperBlockEntity.getContainerAt(level, pos) != null;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		CompoundTag tag = readTag(stack);
		if (tag == null) return;
		byte purpose = purposeFromTag(tag);
		String hint = switch (purpose) {
			case PURPOSE_WAYPOINT -> "Right-click a block to mark a waypoint.";
			case PURPOSE_REGION -> tag.contains(TAG_REGION_A)
					? "Right-click a second block for corner B."
					: "Right-click a block for corner A.";
			case PURPOSE_CHEST_PICKER -> "Right-click a container to set deposit target.";
			default -> "";
		};
		if (!hint.isEmpty()) {
			tooltip.accept(Component.literal(hint).withStyle(ChatFormatting.GRAY));
			tooltip.accept(Component.literal("Sneak + right-click air to cancel.").withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return true;
	}

	public static void clearAllFor(Player player) {
		sweepSessionItems(player, s -> true);
	}

	// 1.21.5+ Inventory.getContainerSize() includes sparse equipment-map indices
	// (mainhand alias, body, saddle) past the menu-visible 41 slots; broadcasting
	// setItem on those crashes the client. Iterate only menu-visible positions.
	private static final EquipmentSlot[] MENU_EQUIPMENT_SLOTS = {
			EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.OFFHAND
	};

	public static void sweepSessionItems(Player player, Predicate<ItemStack> shouldRemove) {
		Inventory inv = player.getInventory();
		NonNullList<ItemStack> items = inv.getNonEquipmentItems();
		for (int i = 0; i < items.size(); i++) {
			ItemStack s = items.get(i);
			if (isSession(s) && shouldRemove.test(s)) inv.setItem(i, ItemStack.EMPTY);
		}
		for (EquipmentSlot slot : MENU_EQUIPMENT_SLOTS) {
			ItemStack s = player.getItemBySlot(slot);
			if (isSession(s) && shouldRemove.test(s)) player.setItemSlot(slot, ItemStack.EMPTY);
		}
	}
}
