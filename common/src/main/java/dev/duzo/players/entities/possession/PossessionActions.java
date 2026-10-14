package dev.duzo.players.entities.possession;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.builder.BlockBreaker;
import dev.duzo.players.platform.Services;
import dev.duzo.players.platform.services.UseCheck;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;

/**
 * What a possessor's clicks do to the world, performed by the fake from its own eyes. Every number a client sends is
 * checked against the fake here, because the packet is the only thing between a modified client and a body it can steer.
 */
final class PossessionActions {
	static final int ATTACK = 0;
	static final int BREAK_START = 1;
	static final int BREAK_STOP = 2;
	static final int USE = 3;
	static final int HOTBAR = 4;

	private static final double ENTITY_REACH = 3.0;
	private static final double BLOCK_REACH = 4.5;
	private static final int ATTACK_COOLDOWN_TICKS = 10;
	private static final int USE_COOLDOWN_TICKS = 4;
	/** Fake hotbar key n (2 to 9) swaps the main hand with storage slot HOTBAR_BASE + n - 2; key 1 is the main hand itself. */
	private static final int HOTBAR_BASE = 27;

	private PossessionActions() {}

	static void handle(ServerPlayer player, PossessionSession s, int action, int targetId, BlockPos pos, int face,
	                   float hx, float hy, float hz, int slot) {
		if (!(s.fake.level() instanceof ServerLevel level)) return;
		switch (action) {
			case ATTACK -> attack(player, s, level, targetId);
			case BREAK_START -> startBreak(player, s, level, pos);
			case BREAK_STOP -> stopBreak(s);
			case USE -> use(player, s, level, pos, face, hx, hy, hz);
			case HOTBAR -> hotbar(s, slot);
			default -> reject(player, "unknown action " + action);
		}
	}

	private static void reject(ServerPlayer player, String why) {
		Constants.traceEvent("possession action rejected {}: {}", player.getUUID(), why);
	}

	private static boolean blockInReach(FakePlayerEntity fake, BlockPos pos) {
		return new AABB(pos).distanceToSqr(fake.getEyePosition()) <= BLOCK_REACH * BLOCK_REACH;
	}

	/** Spawn protection and the world border are judged against the possessor, who is the one acting, and adventure stays adventure. */
	private static boolean mayEdit(ServerPlayer player, PossessionSession s, ServerLevel level, BlockPos pos) {
		if (s.returnMode == GameType.ADVENTURE) return false;
		return level.isInWorldBounds(pos) && level.mayInteract(player, pos);
	}

	/** Things a possessor must not hit: other people's property, and anything inside ground they may not edit. */
	@Nullable
	private static String protectedReason(ServerPlayer player, PossessionSession s, ServerLevel level, Entity target) {
		if (!level.mayInteract(player, target.blockPosition())) return "protected area";
		if (target instanceof HangingEntity || target instanceof ArmorStand) return "decoration";
		if (target instanceof FakePlayerEntity other && !player.getUUID().equals(other.getAIState().ownerUUID())) return "someone else's fake";
		if (target instanceof OwnableEntity pet && pet.getOwnerReference() != null
				&& !pet.getOwnerReference().getUUID().equals(player.getUUID())) return "someone else's pet";
		return null;
	}

	// ---- attack ----

	private static void attack(ServerPlayer player, PossessionSession s, ServerLevel level, int targetId) {
		long now = s.server.getTickCount();
		if (now - s.lastAttackTick < ATTACK_COOLDOWN_TICKS) return;
		FakePlayerEntity fake = s.fake;
		Entity target = level.getEntity(targetId);
		if (target == null || target == fake || target == player || !target.isAlive() || !target.isAttackable()) {
			reject(player, "bad attack target " + targetId);
			return;
		}
		if (target instanceof Player && !level.getServer().isPvpAllowed()) {
			reject(player, "pvp is off");
			return;
		}
		if (target.getBoundingBox().distanceToSqr(fake.getEyePosition()) > ENTITY_REACH * ENTITY_REACH) {
			reject(player, "target out of reach");
			return;
		}
		String refused = protectedReason(player, s, level, target);
		if (refused != null) {
			reject(player, "attack refused, " + refused);
			return;
		}
		s.lastAttackTick = now;
		fake.swing(InteractionHand.MAIN_HAND);
		boolean hit = fake.doHurtTarget(level, target);
		Constants.traceEvent("possession attack {} target={} hit={}", fake.getUUID(), target.getUUID(), hit);
	}

	// ---- break ----

	private static void startBreak(ServerPlayer player, PossessionSession s, ServerLevel level, BlockPos pos) {
		if (!blockInReach(s.fake, pos) || !mayEdit(player, s, level, pos)) {
			reject(player, "cannot break " + pos.toShortString());
			stopBreak(s);
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			stopBreak(s);
			return;
		}
		if (!pos.equals(s.breakPos)) s.breaker.reset(level, s.fake);
		s.breakPos = pos.immutable();
		s.fullNotified = false;
	}

	static void stopBreak(PossessionSession s) {
		if (s.fake.level() instanceof ServerLevel level) s.breaker.reset(level, s.fake);
		s.breakPos = null;
	}

	static void tickBreak(ServerPlayer player, PossessionSession s) {
		BlockPos pos = s.breakPos;
		if (pos == null || !(s.fake.level() instanceof ServerLevel level)) return;
		if (!blockInReach(s.fake, pos) || !mayEdit(player, s, level, pos)) {
			reject(player, "break target left reach " + pos.toShortString());
			stopBreak(s);
			return;
		}
		BlockBreaker.Result result = s.breaker.tick(level, s.fake, pos, at -> {
			boolean ok = Services.PLATFORM.canBreak(level, player, at);
			if (!ok) reject(player, "break vetoed at " + at.toShortString());
			return ok;
		});
		switch (result) {
			case PROGRESS -> {}
			case BROKEN -> {
				Constants.traceEvent("possession break {} at {}", s.fake.getUUID(), pos.toShortString());
				s.breakPos = null;
			}
			case BLOCKED -> s.breakPos = null;
			case FULL -> {
				s.breakPos = null;
				if (!s.fullNotified) {
					s.fullNotified = true;
					s.fake.sendChatTo(player, "My inventory is full.");
				}
			}
		}
	}

	// ---- use ----

	private static boolean isToggle(BlockState state) {
		return state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
				|| state.is(BlockTags.BUTTONS) || state.is(Blocks.LEVER);
	}

	/**
	 * Right-click on a block: doors, gates, trapdoors, buttons and levers toggle, otherwise the main hand is used on it, which
	 * places a block or does whatever the item does to a block. Containers and other menus are never opened, since the
	 * possessor has no screen for the fake's side of them.
	 */
	private static void use(ServerPlayer player, PossessionSession s, ServerLevel level, BlockPos pos, int face,
	                        float hx, float hy, float hz) {
		long now = s.server.getTickCount();
		if (now - s.lastUseTick < USE_COOLDOWN_TICKS) return;
		FakePlayerEntity fake = s.fake;
		if (face < 0 || face > 5 || !Float.isFinite(hx) || !Float.isFinite(hy) || !Float.isFinite(hz)) {
			reject(player, "bad use data");
			return;
		}
		if (!blockInReach(fake, pos) || !mayEdit(player, s, level, pos)) {
			reject(player, "cannot use " + pos.toShortString());
			return;
		}
		Direction direction = Direction.from3DDataValue(face);
		Vec3 hit = new Vec3(pos.getX() + clamp01(hx), pos.getY() + clamp01(hy), pos.getZ() + clamp01(hz));
		BlockHitResult hitResult = new BlockHitResult(hit, direction, pos, false);
		BlockState state = level.getBlockState(pos);
		boolean toggle = isToggle(state);
		UseCheck check = UseCheck.PROCEED;
		if (toggle || !fake.getMainHandItem().isEmpty()) {
			check = Services.PLATFORM.canUse(level, player, hitResult);
			if (check.verdict() == UseCheck.Verdict.DENY) {
				reject(player, "use vetoed at " + pos.toShortString());
				return;
			}
		}
		s.lastUseTick = now;
		boolean usedItem = false;

		InteractionResult result = InteractionResult.PASS;
		if (check.verdict() == UseCheck.Verdict.HANDLED) result = InteractionResult.SUCCESS;
		try {
			if (toggle && check.block() && check.verdict() == UseCheck.Verdict.PROCEED) {
				result = state.useWithoutItem(level, player, hitResult);
			}
			ItemStack held = fake.getMainHandItem();
			if (!result.consumesAction() && !held.isEmpty() && check.item()) {
				if (held.getItem() instanceof BlockItem block) {
					FakePlaceContext context = new FakePlaceContext(level, fake, InteractionHand.MAIN_HAND, held, hitResult);
					BlockPlaceContext placing = block.updatePlacementContext(context);
					BlockState placed = placing == null ? null : block.getBlock().getStateForPlacement(placing);
					BlockPos placePos = placing == null ? context.getClickedPos() : placing.getClickedPos();
					if (placed == null || !mayEdit(player, s, level, placePos)
							|| !Services.PLATFORM.canPlace(level, player, placePos, placed)) {
						reject(player, "place vetoed at " + placePos.toShortString());
						return;
					}
					result = block.place(context);
				} else {
					usedItem = true;
					result = held.useOn(new UseOnContext(level, null, InteractionHand.MAIN_HAND, held, hitResult));
				}
			}
		} catch (RuntimeException e) {
			Constants.LOG.warn("possession use failed for {} at {}", fake.getUUID(), pos, e);
			return;
		}
		if (result.consumesAction()) {
			fake.swing(InteractionHand.MAIN_HAND);
			// the context has no player, so the item skipped its own wear. a door or lever toggle wears nothing
			ItemStack worn = fake.getMainHandItem();
			if (usedItem && worn.isDamageableItem()) worn.hurtAndBreak(1, fake, EquipmentSlot.MAINHAND);
		}
		Constants.traceEvent("possession use {} at {} face={} -> {}", fake.getUUID(), pos.toShortString(), direction, result);
	}

	private static double clamp01(float v) {
		return Math.max(0.0F, Math.min(1.0F, v));
	}

	// ---- hotbar ----

	private static void hotbar(PossessionSession s, int slot) {
		if (slot < 2 || slot > 9) return;
		FakePlayerEntity fake = s.fake;
		int index = HOTBAR_BASE + (slot - 2);
		ItemStack main = fake.getItemBySlot(EquipmentSlot.MAINHAND);
		ItemStack other = fake.getInventory().getItem(index);
		fake.setItemSlot(EquipmentSlot.MAINHAND, other);
		fake.getInventory().setItem(index, main);
		// a different tool changes how fast the block in progress breaks
		stopBreak(s);
		Constants.traceEvent("possession hotbar {} key={} swapped {} <-> {}", fake.getUUID(), slot, main, other);
	}
}
