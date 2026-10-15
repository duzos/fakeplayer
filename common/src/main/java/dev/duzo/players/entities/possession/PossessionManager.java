package dev.duzo.players.entities.possession;

import dev.duzo.players.Constants;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.PossessionLinkItem;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.mixin.PlayerInvoker;
import dev.duzo.players.mixin.PlayerListAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.Containers;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The body swap. A possessing player becomes the fake: it gets the fake's inventory, equipment and vitals and moves to where
 * the fake stands, while the fake stays behind as an invisible, inert shell and a stand-in copy of the player stays where they
 * were. Stacks move between the two and are never copied, and every transition is written to the {@link PossessionLedger}
 * before it is relied on, so a crash can be told apart from a clean state.
 */
public final class PossessionManager {
	private static final Identifier HEALTH_MODIFIER = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "possession_health");
	private static final float FOOD_SATURATION = 5.0F;

	private static final Map<UUID, PossessionSession> SESSIONS = new HashMap<>();
	private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();
	private static final Set<UUID> RELEASING = new HashSet<>();
	private static final List<PendingTeleport> PENDING_TELEPORTS = new ArrayList<>();
	/** True while this class changes a player's game mode itself, so the game mode mixin does not treat it as an outside change. */
	private static boolean ownGameModeChange;

	private PossessionManager() {}

	public enum Reason {
		RELEASED("released"),
		GAME_MODE("game mode changed"),
		DISABLED("disabled"),
		TOO_FAR("too far"),
		UNBOUND("not allowed"),
		LOGOUT("logout"),
		SERVER_STOP("server stopping"),
		BODY_GONE("body gone"),
		BODY_HURT("body hurt"),
		DEATH("death"),
		ERROR("error");

		private final String label;

		Reason(String label) {
			this.label = label;
		}
	}

	private record PendingTeleport(UUID player, String dimension, Vec3 pos, float yaw, float pitch) {}

	public static boolean isPossessing(ServerPlayer player) {
		return SESSIONS.containsKey(player.getUUID());
	}

	// ---- start ----

	/** Item use: checks everything a player could get wrong and says why in the action bar, then starts. */
	public static void tryStart(ServerPlayer player, ItemStack link) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		PlayersConfig config = PlayersConfig.get();
		if (!config.possession) {
			deny(player, "Possession is disabled on this server");
			return;
		}
		if (SESSIONS.containsKey(player.getUUID()) || PossessionStashHolder.stashOf(player) != null) {
			deny(player, "You are already controlling a fake");
			return;
		}
		Long until = COOLDOWN_UNTIL.get(player.getUUID());
		if (until != null && until > server.getTickCount()) {
			deny(player, "Wait " + ((until - server.getTickCount() + 19) / 20) + " more seconds before taking control again");
			return;
		}
		UUID id = PossessionLinkItem.boundFake(link);
		if (id == null) {
			deny(player, "Right-click one of your fakes with this first");
			return;
		}
		if (player.isSpectator()) {
			deny(player, "You cannot take control as a spectator");
			return;
		}
		String unsafe = unsafeToLeave(player);
		if (unsafe != null) {
			deny(player, unsafe);
			return;
		}
		FakePlayerEntity fake = find(server, id);
		if (fake == null || !fake.isAlive() || !fake.isReconciled()) {
			deny(player, "That fake is not loaded, or is gone");
			return;
		}
		if (fake.isVacant()) {
			deny(player, "Someone is already controlling that fake");
			return;
		}
		if (!mayControl(player, fake)) {
			deny(player, "You do not own that fake");
			return;
		}
		int max = config.possessionMaxDistance;
		if (max > 0 && (fake.level() != player.level() || fake.distanceToSqr(player) > (double) max * max)) {
			deny(player, "That fake is more than " + max + " blocks away");
			return;
		}
		start(server, player, link, fake);
	}

	@Nullable
	private static String unsafeToLeave(ServerPlayer player) {
		if (player.isDeadOrDying()) return "You cannot take control right now";
		if (player.isPassenger()) return "You cannot take control while riding";
		if (player.isSleeping()) return "You cannot take control while asleep";
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
			if (level.getEntity(id) instanceof FakePlayerEntity fake && !fake.isRemoved()) return fake;
		}
		return null;
	}

	@Nullable
	private static PossessionBodyEntity findBody(MinecraftServer server, UUID id) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(id) instanceof PossessionBodyEntity body && !body.isRemoved()) return body;
		}
		return null;
	}

	private static String dimensionOf(Level level) {
		return level.dimension().identifier().toString();
	}

	@Nullable
	private static ServerLevel levelOf(MinecraftServer server, String dimension) {
		Identifier id = Identifier.tryParse(dimension);
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	private static void start(MinecraftServer server, ServerPlayer player, ItemStack link, FakePlayerEntity fake) {
		ServerLevel playerLevel = player.level();
		ServerLevel fakeLevel = (ServerLevel) fake.level();
		PossessionLedger ledger = PossessionLedger.of(server);
		PossessionLedger.Entry previous = ledger.get(fake.getUUID());
		long originalEpoch = fake.possessionEpoch();
		long baseEpoch = Math.max(originalEpoch, previous == null ? 0L : previous.epoch);
		long epoch = baseEpoch + 1;
		String fakeName = fake.getSkinData().name();
		Constants.traceEvent("possession start {} fake={} epoch={} from {} {},{},{}", player.getUUID(), fake.getUUID(), epoch,
				dimensionOf(playerLevel), (int) player.getX(), (int) player.getY(), (int) player.getZ());

		int oldDamage = link.getDamageValue();
		int oldCount = link.getCount();
		link.hurtAndBreak(1, playerLevel, player, item -> {});
		AIMarkerItem.clearAllFor(player);
		closeAndFlush(player);
		((PlayerInvoker) player).players$removeEntitiesOnShoulder();

		PossessionBodyEntity body = FPEntities.POSSESSION_BODY.get().create(playerLevel, EntitySpawnReason.TRIGGERED);
		if (body == null) {
			failStart(player, link, oldDamage, oldCount, "the body could not be created");
			return;
		}
		body.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		body.setYHeadRot(player.getYHeadRot());
		body.setYBodyRot(player.getYRot());
		body.setOwner(player.getUUID(), player.getName().getString());
		if (!playerLevel.addFreshEntity(body)) {
			failStart(player, link, oldDamage, oldCount, "the body could not be placed");
			return;
		}

		FakeState fakeState = null;
		PlayerStash stash = null;
		boolean vacated = false;
		boolean committed = false;
		try {
			fakeState = FakeState.takeFrom(fake);
			fake.setVacant(player.getUUID(), epoch);
			vacated = true;
			CompoundTag shell = snapshot(fake);
			PlayerStash.Body bodyRef = new PlayerStash.Body(dimensionOf(playerLevel), player.getX(), player.getY(), player.getZ(),
					player.getYRot(), player.getXRot(), body.getUUID());
			PlayerStash.Ref fakeRef = new PlayerStash.Ref(fake.getUUID(), dimensionOf(fakeLevel), epoch, shell, fakeName);
			stash = PlayerStash.capture(player, bodyRef, fakeRef);
			committed = ledger.put(fake.getUUID(), new PossessionLedger.Entry(epoch, player.getUUID(), true, null));
		} catch (Throwable t) {
			Constants.LOG.error("Possession start failed before the ledger was written", t);
			Constants.traceEvent("possession start threw before commit {}: {}", player.getUUID(), t.toString());
		}
		if (!committed || stash == null || fakeState == null) {
			if (stash != null) stash.restoreTo(player);
			if (vacated) fake.clearVacant(originalEpoch);
			if (fakeState != null) fakeState.applyTo(fake);
			body.discard();
			failStart(player, link, oldDamage, oldCount, "the ledger could not be written");
			return;
		}
		PlayerStash.Body bodyRef = stash.body();

		PossessionStashHolder.setStash(player, stash);
		PossessionSession session = new PossessionSession(player.getUUID(), fake.getUUID(), body.getUUID(), epoch, server.getTickCount(),
				fakeName, bodyRef.dimension(), new Vec3(bodyRef.x(), bodyRef.y(), bodyRef.z()), bodyRef.yaw(), bodyRef.pitch());
		session.bindFake(fake);
		SESSIONS.put(player.getUUID(), session);
		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null) {
			maxHealth.addOrReplacePermanentModifier(new AttributeModifier(HEALTH_MODIFIER, fake.getMaxHealth() - maxHealth.getBaseValue(),
					AttributeModifier.Operation.ADD_VALUE));
		}
		fakeState.applyTo(player);
		syncHeldSlot(player);
		Constants.traceEvent("possession ledger committed {} fake={} epoch={}, items moved to the player", player.getUUID(), fake.getUUID(), epoch);

		try {
			player.getFoodData().setFoodLevel(20);
			player.getFoodData().setSaturation(FOOD_SATURATION);
			GameType mode = stash.gameMode() == GameType.ADVENTURE ? GameType.ADVENTURE : GameType.SURVIVAL;
			setGameModeOwn(player, mode);
			player.resetFallDistance();
			player.setDeltaMovement(Vec3.ZERO);
			player.teleportTo(fakeLevel, fake.getX(), fake.getY(), fake.getZ(), Set.of(), fake.getYRot(), fake.getXRot(), true);
			showBody(body, player);
			syncDisguise(server, player, fake, true);
			save(server, player);
			player.displayClientMessage(Component.translatable("message.players.possession.start", fakeName,
					Component.keybind("key.players.release_possession")), true);
			Constants.traceEvent("possession started {} fake={} mode={}", player.getUUID(), fake.getUUID(), mode);
		} catch (Throwable t) {
			Constants.LOG.error("Possession start failed after the swap, releasing", t);
			Constants.traceEvent("possession start threw {}: {}", player.getUUID(), t.toString());
			release(player, Reason.ERROR, null);
		}
	}

	/** Undoes what start did before anything was moved: the link's wear and the failure message. */
	private static void failStart(ServerPlayer player, ItemStack link, int oldDamage, int oldCount, String why) {
		link.setCount(oldCount);
		link.setDamageValue(oldDamage);
		Constants.traceEvent("possession start aborted {}: {}", player.getUUID(), why);
		player.displayClientMessage(Component.literal("Something stopped you taking control: " + why).withStyle(ChatFormatting.RED), true);
	}

	private static CompoundTag snapshot(FakePlayerEntity fake) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, fake.registryAccess());
		fake.save(out);
		return out.buildResult();
	}

	/** The 2x2 grid and the cursor go back to the inventory before it is captured, so nothing is left in a menu. */
	private static void closeAndFlush(ServerPlayer player) {
		player.closeContainer();
		player.inventoryMenu.removed(player);
		player.connection.send(new ClientboundContainerClosePacket(0));
	}

	private static void showBody(PossessionBodyEntity body, ServerPlayer player) {
		PlayerStash stash = PossessionStashHolder.stashOf(player);
		if (stash == null) return;
		for (Map.Entry<EquipmentSlot, ItemStack> e : stash.own().equipment().entrySet()) body.showItem(e.getKey(), e.getValue());
		List<ItemStack> items = stash.own().items();
		int selected = stash.own().selected();
		if (selected >= 0 && selected < items.size()) body.showItem(EquipmentSlot.MAINHAND, items.get(selected));
	}

	/** The server moved the selected slot, which the client only learns from this. */
	private static void syncHeldSlot(ServerPlayer player) {
		player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
	}

	private static void setGameModeOwn(ServerPlayer player, GameType mode) {
		ownGameModeChange = true;
		try {
			player.setGameMode(mode);
		} finally {
			ownGameModeChange = false;
		}
	}

	private static void save(MinecraftServer server, ServerPlayer player) {
		((PlayerListAccessor) server.getPlayerList()).players$save(player);
		Constants.traceEvent("possession player saved {}", player.getUUID());
	}

	private static void removeHealthModifier(ServerPlayer player) {
		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null) maxHealth.removeModifier(HEALTH_MODIFIER);
	}

	// ---- tick ----

	public static void tick(MinecraftServer server) {
		if (!PENDING_TELEPORTS.isEmpty()) {
			for (PendingTeleport t : new ArrayList<>(PENDING_TELEPORTS)) {
				ServerPlayer player = server.getPlayerList().getPlayer(t.player);
				ServerLevel level = levelOf(server, t.dimension);
				if (player != null && level != null) player.teleportTo(level, t.pos.x, t.pos.y, t.pos.z, Set.of(), t.yaw, t.pitch, true);
			}
			PENDING_TELEPORTS.clear();
		}
		if (SESSIONS.isEmpty()) return;
		for (PossessionSession session : new ArrayList<>(SESSIONS.values())) {
			ServerPlayer player = server.getPlayerList().getPlayer(session.player);
			if (player == null) {
				Constants.traceEvent("possession session dropped {}: player is gone", session.player);
				SESSIONS.remove(session.player);
				continue;
			}
			Reason bad = check(server, player, session);
			if (bad != null) release(player, bad, null);
		}
	}

	@Nullable
	private static Reason check(MinecraftServer server, ServerPlayer player, PossessionSession s) {
		PlayersConfig config = PlayersConfig.get();
		PossessionBodyEntity body = trackBody(server, s);
		if (body == null && bodyChunkLoaded(server, s)) return Reason.BODY_GONE;
		if (!config.possession) return Reason.DISABLED;
		FakePlayerEntity fake = shellOf(server, s);
		if (fake != null && !mayControl(player, fake)) return Reason.UNBOUND;
		int max = config.possessionMaxDistance;
		if (max > 0) {
			boolean sameDimension = dimensionOf(player.level()).equals(s.bodyDimension);
			if (!sameDimension || player.position().distanceToSqr(s.bodyPos) > (double) max * max) return Reason.TOO_FAR;
		}
		return null;
	}

	/** Remembers where the body is, so a body that was pushed, or has unloaded, is still where the player comes back to. */
	@Nullable
	private static PossessionBodyEntity trackBody(MinecraftServer server, PossessionSession s) {
		PossessionBodyEntity body = findBody(server, s.bodyId);
		if (body == null) return null;
		s.bodyDimension = dimensionOf(body.level());
		s.bodyPos = body.position();
		s.bodyYaw = body.getYRot();
		s.bodyPitch = body.getXRot();
		return body;
	}

	/** True when the level the body was last seen in has its chunk loaded, so a missing body there is really gone. */
	private static boolean bodyChunkLoaded(MinecraftServer server, PossessionSession s) {
		ServerLevel level = levelOf(server, s.bodyDimension);
		return level != null && level.areEntitiesLoaded(ChunkPos.asLong(BlockPos.containing(s.bodyPos)));
	}

	@Nullable
	private static FakePlayerEntity shellOf(MinecraftServer server, PossessionSession s) {
		FakePlayerEntity fake = s.fake();
		if (fake != null) return fake;
		fake = find(server, s.fakeId);
		if (fake != null) s.bindFake(fake);
		return fake;
	}

	// ---- release ----

	/** Ends the player's possession, if any. */
	public static void release(ServerPlayer player, Reason reason, @Nullable DamageSource deathSource) {
		MinecraftServer server = player.level().getServer();
		PlayerStash stash = PossessionStashHolder.stashOf(player);
		PossessionSession session = SESSIONS.get(player.getUUID());
		if (server == null || stash == null) {
			if (session != null) SESSIONS.remove(player.getUUID());
			return;
		}
		if (!RELEASING.add(player.getUUID())) return;
		try {
			doRelease(server, player, stash, session, reason, deathSource);
		} catch (Throwable t) {
			Constants.LOG.error("Releasing the possession of {} failed", player.getUUID(), t);
			Constants.traceEvent("possession release threw {}: {}", player.getUUID(), t.toString());
		} finally {
			RELEASING.remove(player.getUUID());
		}
	}

	private static void doRelease(MinecraftServer server, ServerPlayer player, PlayerStash stash, @Nullable PossessionSession session,
	                              Reason reason, @Nullable DamageSource deathSource) {
		UUID fakeId = stash.fake().uuid();
		Constants.traceEvent("possession release {} fake={} reason={}", player.getUUID(), fakeId, reason.label);
		AIMarkerItem.clearAllFor(player);
		closeAndFlush(player);
		ServerLevel level = player.level();
		Vec3 pos = player.position();
		float yaw = player.getYRot();
		float pitch = player.getXRot();

		FakeState state = FakeState.takeFrom(player);
		removeHealthModifier(player);
		stash.restoreTo(player);
		setGameModeOwn(player, stash.gameMode());
		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		syncHeldSlot(player);
		Constants.traceEvent("possession release {} own state restored, {} stacks go to the fake", player.getUUID(), countStacks(state));

		PossessionLedger ledger = PossessionLedger.of(server);
		FakePlayerEntity fake = placeInFake(server, ledger, player.getUUID(), stash, level, pos, yaw, pitch, state);

		PossessionBodyEntity body = findBody(server, stash.body().uuid());
		String bodyDim = session != null ? session.bodyDimension : stash.body().dimension();
		Vec3 bodyPos = session != null ? session.bodyPos : new Vec3(stash.body().x(), stash.body().y(), stash.body().z());
		float bodyYaw = session != null ? session.bodyYaw : stash.body().yaw();
		float bodyPitch = session != null ? session.bodyPitch : stash.body().pitch();
		if (body != null) {
			bodyDim = dimensionOf(body.level());
			bodyPos = body.position();
			bodyYaw = body.getYRot();
			bodyPitch = body.getXRot();
		}

		PossessionStashHolder.setStash(player, null);
		SESSIONS.remove(player.getUUID());
		if (body != null) body.discard();

		if (reason == Reason.DEATH) {
			PENDING_TELEPORTS.add(new PendingTeleport(player.getUUID(), bodyDim, bodyPos, bodyYaw, bodyPitch));
			if (fake != null) killFake(fake, ledger, player.getUUID(), deathSource);
		} else {
			ServerLevel back = levelOf(server, bodyDim);
			if (back != null) player.teleportTo(back, bodyPos.x, bodyPos.y, bodyPos.z, Set.of(), bodyYaw, bodyPitch, true);
		}
		syncDisguise(server, player, null, false);
		if (reason != Reason.LOGOUT) save(server, player);
		if (reason != Reason.LOGOUT && reason != Reason.SERVER_STOP) {
			COOLDOWN_UNTIL.put(player.getUUID(), (long) server.getTickCount() + PlayersConfig.get().possessionCooldownSeconds * 20L);
		}
		tell(player, stash.fake().name(), reason);
		Constants.traceEvent("possession released {} fake={} reason={}", player.getUUID(), fakeId, reason.label);
	}

	/**
	 * Puts the state into the fake, bringing the fake to {@code pos} first, and records the hand-over in the ledger. If there is
	 * no fake to give it to, the stacks are dropped where the player stands, so they are never lost.
	 */
	@Nullable
	private static FakePlayerEntity placeInFake(MinecraftServer server, PossessionLedger ledger, UUID possessor, PlayerStash stash,
	                                            ServerLevel level, Vec3 pos, float yaw, float pitch, FakeState state) {
		UUID fakeId = stash.fake().uuid();
		FakePlayerEntity shell = find(server, fakeId);
		try {
			if (shell != null) {
				Entity moved = shell.teleport(new TeleportTransition(level, pos, Vec3.ZERO, yaw, pitch, TeleportTransition.DO_NOTHING));
				if (moved instanceof FakePlayerEntity f) shell = f;
			} else {
				shell = respawnShell(level, stash, pos, yaw, pitch);
				Constants.traceEvent("possession shell not loaded for {}, respawned from its snapshot: {}", fakeId, shell != null);
			}
		} catch (Throwable t) {
			Constants.LOG.error("Moving the shell of fake {} failed", fakeId, t);
			Constants.traceEvent("possession shell move threw {}: {}", fakeId, t.toString());
		}
		PossessionLedger.Entry current = ledger.get(fakeId);
		long next = Math.max(stash.fake().epoch(), Math.max(current == null ? 0L : current.epoch, shell == null ? 0L : shell.possessionEpoch())) + 1;
		CompoundTag pending = null;
		try {
			pending = state.toTag(server.registryAccess());
		} catch (RuntimeException e) {
			Constants.LOG.error("Could not serialise the state handed back to fake {}", fakeId, e);
		}
		if (shell == null) {
			dropAll(level, pos, state);
			ledger.put(fakeId, new PossessionLedger.Entry(next, possessor, false, null));
			Constants.traceEvent("possession fake {} is gone, its stacks were dropped at the player", fakeId);
			return null;
		}
		FakeState leftovers = FakeState.takeFrom(shell);
		if (leftovers.hasItems()) {
			dropAll(level, shell.position(), leftovers);
			Constants.LOG.error("The shell of fake {} held items while vacant; they were dropped beside it", fakeId);
			Constants.traceEvent("possession shell {} held items, dropped them", fakeId);
		}
		shell.clearVacant(next);
		state.applyTo(shell);
		PossessionLedger.Entry entry = new PossessionLedger.Entry(next, possessor, false, pending);
		entry.markPendingAdopted();
		ledger.put(fakeId, entry);
		Constants.traceEvent("possession fake {} has its state back at epoch {}", fakeId, next);
		return shell;
	}

	@Nullable
	private static FakePlayerEntity respawnShell(ServerLevel level, PlayerStash stash, Vec3 pos, float yaw, float pitch) {
		Entity entity = EntityType.loadEntityRecursive(stash.fake().shellSnapshot(), level, EntitySpawnReason.LOAD, e -> {
			e.snapTo(pos.x, pos.y, pos.z, yaw, pitch);
			return e;
		});
		if (!(entity instanceof FakePlayerEntity fake)) return null;
		fake.setUUID(stash.fake().uuid());
		return level.tryAddFreshEntityWithPassengers(fake) ? fake : null;
	}

	/**
	 * The fake dies with everything the possession handed back, which scatters at its feet. The ledger then forgets the pending
	 * state, so a stale copy of the fake on disk is discarded and cannot bring the items back.
	 */
	private static void killFake(FakePlayerEntity fake, PossessionLedger ledger, UUID possessor, @Nullable DamageSource source) {
		if (!(fake.level() instanceof ServerLevel level)) return;
		DamageSource cause = source != null ? source : level.damageSources().generic();
		Constants.traceEvent("possession death path: fake {} dies at {}", fake.getUUID(), fake.position());
		fake.setHealth(0.0F);
		fake.die(cause);
		long epoch = fake.possessionEpoch() + 1;
		fake.setPossessionEpoch(epoch);
		ledger.put(fake.getUUID(), new PossessionLedger.Entry(epoch, possessor, false, null));
	}

	private static void dropAll(ServerLevel level, Vec3 at, FakeState state) {
		for (ItemStack stack : state.allStacks()) {
			if (!stack.isEmpty()) Containers.dropItemStack(level, at.x, at.y, at.z, stack);
		}
	}

	private static int countStacks(FakeState state) {
		int n = 0;
		for (ItemStack stack : state.allStacks()) if (!stack.isEmpty()) n++;
		return n;
	}

	// ---- hooks called from the mixins, the body and the loaders ----

	/** The player is about to die. A possessor does not: the fake does, and the player is back at their body. */
	public static boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
		if (!SESSIONS.containsKey(player.getUUID())) return false;
		release(player, Reason.DEATH, source);
		return true;
	}

	/** A game mode change that did not come from here ends the possession first, and is then applied as asked. */
	public static void beforeGameMode(ServerPlayer player, GameType mode) {
		if (ownGameModeChange || !SESSIONS.containsKey(player.getUUID())) return;
		release(player, Reason.GAME_MODE, null);
	}

	@Nullable
	public static Component tabName(ServerPlayer player) {
		PlayerStash stash = PossessionStashHolder.stashOf(player);
		return stash == null ? null : Component.literal(stash.fake().name());
	}

	/** The body was hit. The possession ends, and the damage goes to the player, now standing at the body. The body loses nothing. */
	public static void onBodyHurt(PossessionBodyEntity body, ServerLevel level, DamageSource source, float amount) {
		MinecraftServer server = level.getServer();
		PossessionSession session = sessionForBody(body.getUUID());
		if (session == null || server == null) {
			Constants.traceEvent("possession body hit {} with no session, discarding it", body.getUUID());
			body.discard();
			return;
		}
		ServerPlayer owner = server.getPlayerList().getPlayer(session.player);
		if (owner == null) return;
		Constants.traceEvent("possession body hit {} by {} for {}", owner.getUUID(), source.getMsgId(), amount);
		release(owner, Reason.BODY_HURT, null);
		if (!isPossessing(owner)) owner.hurtServer(owner.level(), source, amount);
	}

	/** Runs every second on the server: a body that no session refers to is left over from a crash and removes itself. */
	public static void checkBody(PossessionBodyEntity body, ServerLevel level) {
		PossessionSession session = sessionForBody(body.getUUID());
		if (session == null) {
			Constants.traceEvent("possession orphan body {} discarded", body.getUUID());
			body.discard();
			return;
		}
		MinecraftServer server = level.getServer();
		ServerPlayer owner = server == null ? null : server.getPlayerList().getPlayer(session.player);
		if (owner != null) showBody(body, owner);
	}

	@Nullable
	private static PossessionSession sessionForBody(UUID bodyId) {
		for (PossessionSession s : SESSIONS.values()) if (s.bodyId.equals(bodyId)) return s;
		return null;
	}

	/** A vacant shell was loaded while its possessor is online: the session finds it again. */
	public static void rebind(FakePlayerEntity fake, UUID possessor) {
		PossessionSession session = SESSIONS.get(possessor);
		if (session == null || !session.fakeId.equals(fake.getUUID())) {
			Constants.traceEvent("possession shell {} loaded with no session for {}, waiting for login repair", fake.getUUID(), possessor);
			return;
		}
		session.bindFake(fake);
		Constants.traceEvent("possession shell {} rebound to the session of {}", fake.getUUID(), possessor);
	}

	public static void onDisconnect(ServerPlayer player) {
		release(player, Reason.LOGOUT, null);
	}

	/** Before players and chunks are saved, so nothing is written half way through a possession. */
	public static void onServerStopping(MinecraftServer server) {
		for (UUID id : new ArrayList<>(SESSIONS.keySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) release(player, Reason.SERVER_STOP, null);
		}
		SESSIONS.clear();
		COOLDOWN_UNTIL.clear();
		PENDING_TELEPORTS.clear();
		Constants.traceEvent("possession stop-all done");
	}

	/** After every dimension saved: pending hand-overs whose fake has now been written to disk are forgotten. */
	public static void onAllSaved(MinecraftServer server) {
		PossessionLedger.of(server).onAllSaved();
	}

	/** After one dimension saved, for loaders that report each level on its own. */
	public static void onLevelSaved(MinecraftServer server, ServerLevel level) {
		PossessionLedger.of(server).onLevelSaved(dimensionOf(level));
	}

	/** The server is gone for good; forget everything that was tied to it. */
	public static void onServerStopped() {
		SESSIONS.clear();
		COOLDOWN_UNTIL.clear();
		PENDING_TELEPORTS.clear();
		RELEASING.clear();
		PossessionLedger.forget();
	}

	// ---- login repair ----

	/** A player who logs in holding a stash was in a fake when the server stopped uncleanly: undo it. */
	public static void onJoin(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null || SESSIONS.containsKey(player.getUUID())) return;
		PlayerStash stash = PossessionStashHolder.stashOf(player);
		syncDisguisesTo(server, player);
		if (stash == null) return;
		if (!RELEASING.add(player.getUUID())) return;
		try {
			repair(server, player, stash);
		} catch (Throwable t) {
			Constants.LOG.error("Possession repair for {} failed", player.getUUID(), t);
			Constants.traceEvent("possession repair threw {}: {}", player.getUUID(), t.toString());
		} finally {
			RELEASING.remove(player.getUUID());
		}
	}

	private static void repair(MinecraftServer server, ServerPlayer player, PlayerStash stash) {
		UUID fakeId = stash.fake().uuid();
		Constants.traceEvent("possession repair begin {} fake={} stash epoch={}", player.getUUID(), fakeId, stash.fake().epoch());
		ServerLevel level = player.level();
		Vec3 pos = player.position();
		float yaw = player.getYRot();
		float pitch = player.getXRot();

		FakeState state = FakeState.takeFrom(player);
		removeHealthModifier(player);
		stash.restoreTo(player);
		setGameModeOwn(player, stash.gameMode());
		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		syncHeldSlot(player);

		PossessionLedger ledger = PossessionLedger.of(server);
		PossessionLedger.Entry entry = ledger.get(fakeId);
		boolean over = entry != null && (!entry.active || entry.epoch > stash.fake().epoch());
		if (over) {
			Constants.traceEvent("possession repair {} fake={} ledger epoch {} active={} is past the stash, discarding the player's copy of {} stacks",
					player.getUUID(), fakeId, entry.epoch, entry.active, countStacks(state));
		} else {
			placeInFake(server, ledger, player.getUUID(), stash, level, pos, yaw, pitch, state);
		}

		PossessionBodyEntity body = findBody(server, stash.body().uuid());
		if (body != null) body.discard();
		ServerLevel back = levelOf(server, stash.body().dimension());
		PossessionStashHolder.setStash(player, null);
		if (back != null) player.teleportTo(back, stash.body().x(), stash.body().y(), stash.body().z(), Set.of(), stash.body().yaw(), stash.body().pitch(), true);
		syncDisguise(server, player, null, false);
		save(server, player);
		tell(player, stash.fake().name(), null);
		Constants.traceEvent("possession repair done {} fake={}", player.getUUID(), fakeId);
	}

	// ---- messages and sync ----

	private static void tell(ServerPlayer player, String fakeName, @Nullable Reason reason) {
		Component line = switch (reason) {
			case null -> Component.literal("Your possession of " + fakeName + " ended while you were away.");
			case RELEASED, LOGOUT, SERVER_STOP -> null;
			case GAME_MODE -> Component.literal("Your game mode changed, so you are back in your own body.");
			case DISABLED -> Component.literal("Possession was turned off on this server.");
			case TOO_FAR -> Component.literal("You went too far from your body.");
			case UNBOUND -> Component.literal("You can no longer control " + fakeName + ".");
			case BODY_GONE -> Component.literal("Your body is gone, so you are back.");
			case BODY_HURT -> Component.literal("Your body was hit.");
			case DEATH -> Component.literal(fakeName + " died. You are back in your own body.");
			case ERROR -> Component.literal("Something went wrong, so you are back in your own body.");
		};
		if (line != null) player.displayClientMessage(line.copy().withStyle(ChatFormatting.YELLOW), reason == null || reason == Reason.DEATH);
	}

	/** Refreshes the tab list name, and in time the disguise, for everyone. */
	private static void syncDisguise(MinecraftServer server, ServerPlayer player, @Nullable FakePlayerEntity fake, boolean on) {
		server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
				EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), List.of(player)));
		Constants.traceEvent("possession disguise sync {} on={}", player.getUUID(), on);
	}

	private static void syncDisguisesTo(MinecraftServer server, ServerPlayer joiner) {}
}
