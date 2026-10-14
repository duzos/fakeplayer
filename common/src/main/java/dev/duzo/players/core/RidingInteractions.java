package dev.duzo.players.core;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Seating a fake with its own bound AI marker. Called from each loader's use-entity and use-block event, because
 * neither a boat nor a seat block ever reaches the item itself.
 */
public final class RidingInteractions {
	private static final double SEAT_RANGE_SQ = 16.0D * 16.0D;

	private RidingInteractions() {}

	/** Marker used on a vehicle: the bound fake climbs aboard. PASS when this is not our business. */
	public static InteractionResult useEntity(Player player, Level level, InteractionHand hand, Entity target) {
		FakePlayerEntity fake = boundFake(player, level, hand);
		if (fake == null) return InteractionResult.PASS;
		if (target instanceof Player || target instanceof LivingEntity) return InteractionResult.PASS;
		if (!allowed(player, fake)) return InteractionResult.FAIL;
		if (isContraption(target)) {
			tell(player, "Use the marker on the seat block instead.", ChatFormatting.RED);
			return InteractionResult.FAIL;
		}
		if (fake.distanceToSqr(target) > SEAT_RANGE_SQ) {
			tell(player, "That is too far from the fake.", ChatFormatting.RED);
			return InteractionResult.FAIL;
		}
		if (fake.getVehicle() == target) {
			tell(player, "Already seated there.", ChatFormatting.YELLOW);
			return InteractionResult.SUCCESS;
		}
		prepare(fake);
		if (!fake.startRiding(target)) {
			Constants.traceEvent("seat refused {} on {}", fake.getUUID(), target);
			tell(player, "The fake cannot sit there.", ChatFormatting.RED);
			return InteractionResult.FAIL;
		}
		fake.setOrderedSeat(true);
		Constants.traceEvent("seat ordered {} on {}", fake.getUUID(), target);
		tell(player, "Seated.", ChatFormatting.GREEN);
		return InteractionResult.SUCCESS;
	}

	/** Marker used on a Create seat block: the fake is dropped onto it and Create's own landing seats it. */
	public static InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		if (!isSeatBlock(level.getBlockState(pos))) return InteractionResult.PASS;
		FakePlayerEntity fake = boundFake(player, level, hand);
		if (fake == null) return InteractionResult.PASS;
		if (!allowed(player, fake)) return InteractionResult.FAIL;
		if (fake.distanceToSqr(Vec3.atCenterOf(pos)) > SEAT_RANGE_SQ) {
			tell(player, "That is too far from the fake.", ChatFormatting.RED);
			return InteractionResult.FAIL;
		}
		prepare(fake);
		if (fake.isRiding()) fake.stopRiding();
		fake.getNavigation().stop();
		fake.setDeltaMovement(Vec3.ZERO);
		// a little above the seat, so it lands on it the way a falling mob does
		fake.teleportTo(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D);
		fake.setOrderedSeat(true);
		Constants.traceEvent("seat ordered {} onto seat block {}", fake.getUUID(), pos);
		tell(player, "Seated.", ChatFormatting.GREEN);
		return InteractionResult.SUCCESS;
	}

	/** Create's seats are blocks named {@code create:<colour>_seat}; matched by id so there is no compile dependency. */
	public static boolean isSeatBlock(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id.getNamespace().equals("create") && id.getPath().endsWith("seat");
	}

	private static boolean isContraption(Entity entity) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		return id.getNamespace().equals("create") && id.getPath().contains("contraption");
	}

	private static void prepare(FakePlayerEntity fake) {
		if (fake.bedClaim() != null) fake.releaseBedClaim();
		if (fake.isBedSleeping()) fake.stopSleeping();
		if (fake.getPhysicalState() == FakePlayerEntity.PhysicalState.LAYING) {
			fake.setPhysicalState(FakePlayerEntity.PhysicalState.STANDING);
		}
	}

	private static boolean allowed(Player player, FakePlayerEntity fake) {
		UUID owner = fake.getAIState().ownerUUID();
		if (owner == null || owner.equals(player.getUUID()) || AIMarkerItem.isOp(player)) return true;
		tell(player, "You do not own this fake.", ChatFormatting.RED);
		return false;
	}

	@Nullable
	private static FakePlayerEntity boundFake(Player player, Level level, InteractionHand hand) {
		if (!(level instanceof ServerLevel server) || hand != InteractionHand.MAIN_HAND) return null;
		ItemStack stack = player.getItemInHand(hand);
		if (!AIMarkerItem.isSession(stack)) return null;
		UUID id = AIMarkerItem.fakeUUID(stack);
		return id != null && server.getEntity(id) instanceof FakePlayerEntity fake ? fake : null;
	}

	private static void tell(Player player, String message, ChatFormatting color) {
		player.displayClientMessage(Component.literal(message).withStyle(color), true);
	}
}
