package dev.duzo.players.core;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import javax.annotation.Nullable;
import java.util.List;

public class AIMarkerItem extends Item {
	public static final byte MODE_WAYPOINT = 0;
	public static final byte MODE_REGION = 1;
	public static final byte MODE_DEPOSIT = 2;

	private static final String TAG_ENTITY = "FpEntityId";
	private static final String TAG_MODE = "FpMode";
	private static final String TAG_REGION_A = "FpRegionA";

	public AIMarkerItem(Properties properties) {
		super(properties.stacksTo(1));
	}

	public static ItemStack make(int entityId, byte mode) {
		ItemStack stack = new ItemStack(FPItems.AI_MARKER.get());
		CompoundTag tag = stack.getOrCreateTag();
		tag.putInt(TAG_ENTITY, entityId);
		tag.putByte(TAG_MODE, mode);
		stack.setHoverName(Component.literal(modeLabel(mode)).withStyle(ChatFormatting.AQUA));
		return stack;
	}

	private static String modeLabel(byte mode) {
		return switch (mode) {
			case MODE_WAYPOINT -> "Waypoint Marker";
			case MODE_REGION -> "Region Marker";
			case MODE_DEPOSIT -> "Deposit Marker";
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
		if (tag == null || !tag.contains(TAG_ENTITY) || !tag.contains(TAG_MODE)) {
			player.displayClientMessage(Component.literal("This marker is unbound.").withStyle(ChatFormatting.RED), true);
			return InteractionResult.FAIL;
		}

		ServerLevel level = (ServerLevel) ctx.getLevel();
		Entity raw = level.getEntity(tag.getInt(TAG_ENTITY));
		if (!(raw instanceof FakePlayerEntity entity)) {
			player.displayClientMessage(Component.literal("Target fake player not found.").withStyle(ChatFormatting.RED), true);
			stack.shrink(1);
			return InteractionResult.FAIL;
		}

		byte mode = tag.getByte(TAG_MODE);
		BlockPos pos = ctx.getClickedPos();

		switch (mode) {
			case MODE_WAYPOINT -> {
				entity.mutateAIState(s -> s.setWaypoint(pos.immutable()));
				player.displayClientMessage(Component.literal("Waypoint set.").withStyle(ChatFormatting.GREEN), true);
				stack.shrink(1);
			}
			case MODE_REGION -> {
				if (!tag.contains(TAG_REGION_A)) {
					tag.putLong(TAG_REGION_A, pos.asLong());
					player.displayClientMessage(Component.literal("Region corner A set, click another block for B.").withStyle(ChatFormatting.YELLOW), true);
				} else {
					BlockPos a = BlockPos.of(tag.getLong(TAG_REGION_A));
					BlockPos b = pos.immutable();
					entity.mutateAIState(s -> {
						s.setRegionA(a);
						s.setRegionB(b);
					});
					player.displayClientMessage(Component.literal("Region set.").withStyle(ChatFormatting.GREEN), true);
					stack.shrink(1);
				}
			}
			case MODE_DEPOSIT -> {
				BlockEntity be = ctx.getLevel().getBlockEntity(pos);
				if (!(be instanceof ChestBlockEntity)) {
					player.displayClientMessage(Component.literal("Right-click a chest.").withStyle(ChatFormatting.RED), true);
					return InteractionResult.FAIL;
				}
				entity.mutateAIState(s -> s.setDepositChest(pos.immutable()));
				player.displayClientMessage(Component.literal("Deposit chest set.").withStyle(ChatFormatting.GREEN), true);
				stack.shrink(1);
			}
		}

		return InteractionResult.CONSUME;
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		CompoundTag tag = stack.getTag();
		if (tag == null) return;
		byte mode = tag.getByte(TAG_MODE);
		String hint = switch (mode) {
			case MODE_WAYPOINT -> "Right-click a block to mark a waypoint.";
			case MODE_REGION -> tag.contains(TAG_REGION_A)
					? "Right-click a second block for corner B."
					: "Right-click a block for corner A.";
			case MODE_DEPOSIT -> "Right-click a chest to set deposit target.";
			default -> "";
		};
		if (!hint.isEmpty()) {
			tooltip.add(Component.literal(hint).withStyle(ChatFormatting.GRAY));
		}
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return true;
	}
}
