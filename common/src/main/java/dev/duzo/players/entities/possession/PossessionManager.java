package dev.duzo.players.entities.possession;

import dev.duzo.players.Constants;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.PossessionLinkItem;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Takes a player into a fake's point of view. The player becomes a spectator whose camera is the fake, which vanilla
 * already follows across chunks and dimensions; this class pairs that with the fake's controls, keeps a return record on
 * the Possession Link so a crash or logout can be undone, and ends the possession whenever any part of that stops holding.
 */
public final class PossessionManager {
	/** Ticks of silence from the client after which held movement is dropped, so a lost packet cannot walk a fake off a cliff. */
	public static final int INPUT_TIMEOUT_TICKS = 10;
	/** Ticks the server waits for the first input packet before deciding the client never saw the camera change. */
	private static final int ACK_TIMEOUT_TICKS = 300;
	/** The camera packet is ignored by a client that does not know the entity yet, so it is resent until the client answers. */
	private static final int CAMERA_RESEND_TICKS = 10;
	/** How long after a hit, dealt or taken, a player counts as still fighting. */
	private static final int COMBAT_TICKS = 100;
	/** Clicks a client can land per tick; real play is one or two, so the rest are a modified client flooding the fake. */
	private static final int MAX_ACTIONS_PER_TICK = 4;
	/** How many blocks below the saved return point to look for ground before giving up on it. */
	private static final int GROUND_SEARCH = 4;

	/** How far from the saved return point to look for somewhere safe to stand. */
	private static final int SAFE_RADIUS = 4;

	public static final int FLAG_FORWARD = 1;
	public static final int FLAG_BACK = 2;
	public static final int FLAG_LEFT = 4;
	public static final int FLAG_RIGHT = 8;
	public static final int FLAG_JUMP = 16;
	public static final int FLAG_MASK = 31;

	private static final Map<UUID, PossessionSession> SESSIONS = new HashMap<>();

	private PossessionManager() {}

	public enum Reason {
		/** Sneak, or any teleport: vanilla dropped the camera. */
		RELEASED("released"),
		FAKE_GONE("fake gone"),
		GAME_MODE("game mode changed"),
		DISABLED("disabled"),
		TOO_FAR("too far"),
		UNBOUND("not allowed"),
		NO_CLIENT("no client"),
		LOGOUT("logout"),
		DEATH("death"),
		REPAIR("repair");

		private final String label;

		Reason(String label) {
			this.label = label;
		}
	}

	public static boolean isPossessing(ServerPlayer player) {
		return SESSIONS.containsKey(player.getUUID());
	}

	/** The fake this player controls right now, or null. */
	@Nullable
	public static FakePlayerEntity possessed(ServerPlayer player) {
		PossessionSession session = SESSIONS.get(player.getUUID());
		return session == null ? null : session.fake;
	}

	// ---- start ----

	/** Item use: checks everything a player could get wrong and says why in the action bar, then starts. */
	public static void tryStart(ServerPlayer player, ItemStack link) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		if (!PlayersConfig.get().possession) {
			deny(player, "Possession is disabled on this server");
			return;
		}
		if (SESSIONS.containsKey(player.getUUID()) || player.isSpectator()) return;
		UUID id = PossessionLinkItem.boundFake(link);
		if (id == null) {
			deny(player, "Right-click one of your fakes with this first");
			return;
		}
		String unsafe = unsafeToLeave(player);
		if (unsafe != null) {
			deny(player, unsafe);
			return;
		}
		FakePlayerEntity fake = find(server, id);
		if (fake == null || !fake.isAlive()) {
			deny(player, "That fake is not loaded, or is gone");
			return;
		}
		if (!mayControl(player, fake)) {
			deny(player, "You do not own that fake");
			return;
		}
		if (fake.isPossessed()) {
			deny(player, "Someone is already controlling that fake");
			return;
		}
		int max = PlayersConfig.get().possessionMaxDistance;
		if (max > 0 && (fake.level() != player.level() || fake.distanceToSqr(player) > (double) max * max)) {
			deny(player, "That fake is more than " + max + " blocks away");
			return;
		}
		start(server, player, link, fake);
	}

	/** A spectator cannot be hurt, so nobody gets to use this as an escape from a fight, a fall, a fire or lava. */
	@Nullable
	private static String unsafeToLeave(ServerPlayer player) {
		int age = player.tickCount;
		boolean hurtRecently = player.hurtTime > 0 || player.getLastDamageSource() != null
				|| (player.getLastHurtByMobTimestamp() > 0 && age - player.getLastHurtByMobTimestamp() < COMBAT_TICKS)
				|| (player.getLastHurtMobTimestamp() > 0 && age - player.getLastHurtMobTimestamp() < COMBAT_TICKS);
		if (hurtRecently) return "You cannot take control while in a fight";
		if (player.isInLava() || player.isOnFire()) return "You cannot take control while on fire";
		if (!player.onGround() || player.fallDistance > 3.0) return "You cannot take control while falling";
		return null;
	}

	private static void deny(ServerPlayer player, String message) {
		Constants.traceEvent("possession denied {}: {}", player.getUUID(), message);
		player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), true);
	}

	private static boolean mayControl(ServerPlayer player, FakePlayerEntity fake) {
		return player.getUUID().equals(fake.getAIState().ownerUUID()) || AIMarkerItem.isOp(player);
	}

	@Nullable
	private static FakePlayerEntity find(MinecraftServer server, UUID id) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(id) instanceof FakePlayerEntity fake) return fake;
		}
		return null;
	}

	private static void start(MinecraftServer server, ServerPlayer player, ItemStack link, FakePlayerEntity fake) {
		GameType mode = player.gameMode.getGameModeForPlayer();
		if (fake.isPassenger()) fake.stopRiding();
		PossessionReturn ret = new PossessionReturn(player.level().dimension().identifier().toString(),
				player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), mode.getId(), fake.getUUID());
		PossessionLinkItem.writeReturn(link, ret);
		player.closeContainer();
		fake.setPossessor(player.getId());
		if (!player.setGameMode(GameType.SPECTATOR)) {
			fake.setPossessor(-1);
			PossessionLinkItem.clearReturn(link);
			deny(player, "Something stopped the game mode change");
			return;
		}
		PossessionSession session = new PossessionSession(server, player.getUUID(), fake, ret, mode);
		SESSIONS.put(player.getUUID(), session);
		player.setCamera(fake);
		Constants.traceEvent("possession start {} fake={} from {} {},{},{} mode={}", player.getUUID(), fake.getUUID(),
				ret.dimension(), (int) ret.x(), (int) ret.y(), (int) ret.z(), mode);
		fake.sendChatTo(player, "You have control. Sneak to let go.");
	}

	// ---- tick ----

	public static void tick(MinecraftServer server) {
		if (SESSIONS.isEmpty()) return;
		for (PossessionSession session : new ArrayList<>(SESSIONS.values())) {
			ServerPlayer player = session.server == server ? server.getPlayerList().getPlayer(session.player) : null;
			if (player == null) {
				// a stale session from a stopped server, or a player who is gone: all that is left to undo is the fake's lock
				SESSIONS.remove(session.player);
				if (!session.fake.isRemoved()) session.fake.setPossessor(-1);
				continue;
			}
			tickSession(server, player, session);
		}
	}

	private static void tickSession(MinecraftServer server, ServerPlayer player, PossessionSession s) {
		Reason bad = check(player, s);
		if (bad != null) {
			finish(server, player, s, s.returnPoint, s.fake, bad);
			return;
		}
		long now = server.getTickCount();
		if (s.fake.isPassenger()) s.fake.stopRiding();
		if (!s.acked) {
			long age = now - s.startTick;
			if (age > ACK_TIMEOUT_TICKS) {
				finish(server, player, s, s.returnPoint, s.fake, Reason.NO_CLIENT);
				return;
			}
			if (age % CAMERA_RESEND_TICKS == 0) player.connection.send(new ClientboundSetCameraPacket(s.fake));
			return;
		}
		if (now - s.lastInputTick > INPUT_TIMEOUT_TICKS) {
			s.flags = 0;
			s.fake.setPossessedMove(0.0F, 0.0F, false);
			if (s.breakPos != null) PossessionActions.stopBreak(s);
		}
		if (s.breakPos != null) PossessionActions.tickBreak(player, s);
	}

	@Nullable
	private static Reason check(ServerPlayer player, PossessionSession s) {
		FakePlayerEntity fake = s.fake;
		PlayersConfig config = PlayersConfig.get();
		if (fake.isRemoved() || !fake.isAlive()) return Reason.FAKE_GONE;
		if (!config.possession) return Reason.DISABLED;
		if (!player.isSpectator()) return Reason.GAME_MODE;
		if (player.getCamera() != fake || fake.getPossessor() != player.getId()) return Reason.RELEASED;
		if (!mayControl(player, fake)) return Reason.UNBOUND;
		int max = config.possessionMaxDistance;
		if (max > 0) {
			PossessionReturn ret = s.returnPoint;
			boolean sameDimension = fake.level().dimension().identifier().toString().equals(ret.dimension());
			if (!sameDimension || fake.position().distanceToSqr(ret.x(), ret.y(), ret.z()) > (double) max * max) return Reason.TOO_FAR;
		}
		return null;
	}

	// ---- input from the client ----

	/** Movement and rotation from the possessor's client. Anything not from the current possessor of {@code fakeId} is dropped. */
	public static void onInput(ServerPlayer player, int fakeId, float yaw, float pitch, int flags) {
		PossessionSession s = sessionFor(player, fakeId, "input");
		if (s == null) return;
		if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
			Constants.traceEvent("possession input rejected {}: non-finite rotation", player.getUUID());
			return;
		}
		float cleanYaw = net.minecraft.util.Mth.wrapDegrees(yaw);
		float cleanPitch = net.minecraft.util.Mth.clamp(pitch, -90.0F, 90.0F);
		s.acked = true;
		s.lastInputTick = s.server.getTickCount();
		s.flags = flags & FLAG_MASK;
		float forward = ((s.flags & FLAG_FORWARD) != 0 ? 1.0F : 0.0F) - ((s.flags & FLAG_BACK) != 0 ? 1.0F : 0.0F);
		float strafe = ((s.flags & FLAG_LEFT) != 0 ? 1.0F : 0.0F) - ((s.flags & FLAG_RIGHT) != 0 ? 1.0F : 0.0F);
		s.fake.setPossessedLook(cleanYaw, cleanPitch);
		s.fake.setPossessedMove(forward, strafe, (s.flags & FLAG_JUMP) != 0);
	}

	/** A click from the possessor's client. Validation of the click itself lives in {@link PossessionActions}. */
	public static void onAction(ServerPlayer player, int fakeId, int action, int targetId, BlockPos pos, int face, float hx, float hy, float hz, int slot) {
		PossessionSession s = sessionFor(player, fakeId, "action " + action);
		if (s == null) return;
		if (!s.acked) {
			Constants.traceEvent("possession action rejected {}: no input yet", player.getUUID());
			return;
		}
		long now = s.server.getTickCount();
		if (s.actionTick != now) {
			s.actionTick = now;
			s.actionCount = 0;
		}
		if (++s.actionCount > MAX_ACTIONS_PER_TICK) {
			Constants.traceEvent("possession action dropped {}: more than {} this tick", player.getUUID(), MAX_ACTIONS_PER_TICK);
			return;
		}
		PossessionActions.handle(player, s, action, targetId, pos, face, hx, hy, hz, slot);
	}

	@Nullable
	private static PossessionSession sessionFor(ServerPlayer player, int fakeId, String what) {
		PossessionSession s = SESSIONS.get(player.getUUID());
		if (s == null || s.fake.getId() != fakeId || s.fake.getPossessor() != player.getId() || s.fake.isRemoved()) {
			Constants.traceEvent("possession {} rejected {}: not the possessor of {}", what, player.getUUID(), fakeId);
			return null;
		}
		return s;
	}

	// ---- end ----

	/** Ends the player's possession, if any. */
	public static void end(ServerPlayer player, Reason reason) {
		PossessionSession s = SESSIONS.get(player.getUUID());
		if (s == null) return;
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		finish(server, player, s, s.returnPoint, s.fake, reason);
	}

	public static void onDisconnect(ServerPlayer player) {
		end(player, Reason.LOGOUT);
	}

	public static void onDeath(ServerPlayer player) {
		end(player, Reason.DEATH);
	}

	/**
	 * A player who logged out, or whose server stopped, mid-possession comes back as a spectator standing at the fake with
	 * nothing in memory. The Possession Link in their inventory still has the record, so undo it from that.
	 */
	public static void onJoin(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null || SESSIONS.containsKey(player.getUUID())) return;
		ItemStack link = findActiveLink(player);
		if (link == null) return;
		PossessionReturn ret = PossessionLinkItem.readReturn(link);
		if (ret == null) return;
		finish(server, player, null, ret, null, Reason.REPAIR);
	}

	@Nullable
	private static ItemStack findActiveLink(ServerPlayer player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.getItem() instanceof PossessionLinkItem && PossessionLinkItem.readReturn(stack) != null) return stack;
		}
		return null;
	}

	private static void finish(MinecraftServer server, ServerPlayer player, @Nullable PossessionSession s, PossessionReturn ret,
	                           @Nullable FakePlayerEntity fake, Reason reason) {
		SESSIONS.remove(player.getUUID());
		if (s != null && fake != null && fake.level() instanceof ServerLevel level) s.breaker.reset(level, fake);
		if (fake != null && !fake.isRemoved() && fake.getPossessor() == player.getId()) fake.setPossessor(-1);

		player.setCamera(player);
		ServerLevel back = levelOf(server, ret.dimension());
		Spot spot = safeSpot(server, player, back, ret);
		player.teleportTo(spot.level, spot.x, spot.y, spot.z, Set.of(), ret.yRot(), ret.xRot(), true);
		// only a spectator is still in the mode this class put them in; anyone else changed it themselves
		if (player.isSpectator()) player.setGameMode(GameType.byId(ret.gameMode()));

		ItemStack link = findActiveLink(player);
		if (link != null) {
			PossessionLinkItem.clearReturn(link);
			link.hurtAndBreak(1, player.level(), player, item -> {});
		}
		Constants.traceEvent("possession end {} fake={} reason={}", player.getUUID(), ret.fake(), reason.label);
		if (reason != Reason.LOGOUT) tell(player, s == null ? null : s.fakeName, fake, reason);
	}

	private record Spot(ServerLevel level, double x, double y, double z) {}

	/**
	 * The return point if it is still fit to stand on, else the nearest spot within {@link #SAFE_RADIUS} that is, else where
	 * vanilla would respawn the player, which is also where a return dimension that no longer exists sends them. The world may
	 * have changed a lot since the possession began.
	 */
	private static Spot safeSpot(MinecraftServer server, ServerPlayer player, @Nullable ServerLevel level, PossessionReturn ret) {
		if (level == null) return respawnSpot(player);
		BlockPos origin = BlockPos.containing(ret.x(), ret.y(), ret.z());
		GameType mode = GameType.byId(ret.gameMode());
		// someone who can fly may have been standing on nothing
		if ((mode.isCreative() || mode == GameType.SPECTATOR) && standable(level, origin, false)) {
			return new Spot(level, ret.x(), ret.y(), ret.z());
		}
		if (standable(level, origin, false)) {
			BlockPos ground = groundBelow(level, origin);
			if (ground != null) return ground.equals(origin) ? new Spot(level, ret.x(), ret.y(), ret.z()) : spotAt(level, ground);
		}
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-SAFE_RADIUS, -SAFE_RADIUS, -SAFE_RADIUS), origin.offset(SAFE_RADIUS, SAFE_RADIUS, SAFE_RADIUS))) {
			double dist = pos.distSqr(origin);
			if (dist < bestDist && standable(level, pos, true)) {
				best = pos.immutable();
				bestDist = dist;
			}
		}
		if (best != null) return spotAt(level, best);
		return respawnSpot(player);
	}

	/** Where vanilla would respawn the player: their bed or anchor if it still works, else the world spawn. */
	private static Spot respawnSpot(ServerPlayer player) {
		TeleportTransition respawn = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
		Vec3 at = respawn.position();
		return new Spot(respawn.newLevel(), at.x, at.y, at.z);
	}

	private static Spot spotAt(ServerLevel level, BlockPos feet) {
		return new Spot(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
	}

	/** The feet position at or a few blocks under {@code feet} that has room and something solid beneath, or null. */
	@Nullable
	private static BlockPos groundBelow(ServerLevel level, BlockPos feet) {
		for (int drop = 0; drop <= GROUND_SEARCH; drop++) {
			BlockPos pos = feet.below(drop);
			if (standable(level, pos, true)) return pos;
		}
		return null;
	}

	/** Feet and head room free of anything solid, fluid or burning, and when asked, something to stand on. */
	private static boolean standable(ServerLevel level, BlockPos feet, boolean needGround) {
		if (!level.isInWorldBounds(feet)) return false;
		for (BlockPos pos : new BlockPos[] {feet, feet.above()}) {
			BlockState state = level.getBlockState(pos);
			if (!state.getCollisionShape(level, pos).isEmpty() || state.is(BlockTags.FIRE) || !state.getFluidState().isEmpty()) return false;
		}
		if (!needGround) return true;
		BlockPos below = feet.below();
		BlockState ground = level.getBlockState(below);
		return ground.isFaceSturdy(level, below, Direction.UP) && !ground.is(Blocks.MAGMA_BLOCK) && !ground.is(Blocks.CAMPFIRE);
	}

	@Nullable
	private static ServerLevel levelOf(MinecraftServer server, String dimension) {
		Identifier id = Identifier.tryParse(dimension);
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	private static void tell(ServerPlayer player, @Nullable String name, @Nullable FakePlayerEntity fake, Reason reason) {
		String line = switch (reason) {
			case FAKE_GONE -> null;
			case DISABLED -> "Possession was turned off on this server.";
			case TOO_FAR -> "I got too far from where you started.";
			case UNBOUND -> "You can no longer control me.";
			case NO_CLIENT -> "I lost contact with your game.";
			case GAME_MODE -> "Your game mode changed, so I am mine again.";
			case DEATH, LOGOUT, REPAIR -> null;
			case RELEASED -> "Back to normal.";
		};
		boolean present = fake != null && !fake.isRemoved() && fake.isAlive();
		if (line != null && present) {
			fake.sendChatTo(player, line);
		} else if (reason == Reason.FAKE_GONE) {
			player.displayClientMessage(Component.literal((name == null ? "The fake" : name) + " is gone, so control ended.").withStyle(ChatFormatting.RED), false);
		} else if (reason == Reason.REPAIR) {
			player.displayClientMessage(Component.literal("Your possession ended while you were away.").withStyle(ChatFormatting.YELLOW), true);
		}
	}
}
