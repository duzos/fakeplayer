package dev.duzo.players.entities.possession;

import commonnetwork.api.Network;
import dev.duzo.players.Constants;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.PossessionLinkItem;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.mixin.PlayerInvoker;
import dev.duzo.players.mixin.PlayerListAccessor;
import dev.duzo.players.network.s2c.PossessionDisguiseS2C;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
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
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;

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
	private static final ResourceLocation HEALTH_MODIFIER = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "possession_health");
	private static final float FOOD_SATURATION = 5.0F;

	private static final Map<UUID, PossessionSession> SESSIONS = new HashMap<>();
	private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();
	private static final Set<UUID> RELEASING = new HashSet<>();
	private static final List<PendingTeleport> PENDING_TELEPORTS = new ArrayList<>();
	private static final Map<UUID, FakePlayerEntity> OUTPUTS = new HashMap<>();
	private static final Map<UUID, Integer> CHECKPOINT_RETRY = new HashMap<>();
	private static final Map<UUID, DamageSource> DEATH_SOURCES = new HashMap<>();
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
		PossessionLedger journal = PossessionLedger.of(server);
		if (!journal.available()) { deny(player, "Possession storage is unavailable"); return; }
		var last = journal.latestFor(player.getUUID());
		if (last != null && last.getValue().hasOutput()) { deny(player, "Your previous possession is still being saved"); return; }
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
		if (fake == null || !fake.isAlive() || !fake.isReconciled() || fake.isUnreconciled()) {
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
		return level.dimension().location().toString();
	}

	@Nullable
	private static ServerLevel levelOf(MinecraftServer server, String dimension) {
		ResourceLocation id = ResourceLocation.tryParse(dimension);
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	private static void start(MinecraftServer server, ServerPlayer player, ItemStack link, FakePlayerEntity fake) {
		ServerLevel playerLevel = player.serverLevel();
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
			committed = ledger.put(fake.getUUID(), new PossessionLedger.Entry(epoch, player.getUUID(), true,
					fakeState.toTag(server.registryAccess()), encodeStash(server, stash), shell, dimensionOf(fakeLevel),
					false, ledger.nextSequence(), UUID.randomUUID().toString()));
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
		PossessionFaults.boundary("after-start-prepare");
		PlayerStash.Body bodyRef = stash.body();

		PossessionStashHolder.setStash(player, stash);
		((PossessionStashHolder) player).players$setSaveToken(ledger.get(fake.getUUID()).playerToken);
		PossessionSession session = new PossessionSession(player.getUUID(), fake.getUUID(), body.getUUID(), epoch, server.getTickCount(),
				fakeName, disguiseOf(player, fake, fakeName), bodyRef.dimension(), new Vec3(bodyRef.x(), bodyRef.y(), bodyRef.z()), bodyRef.yaw(), bodyRef.pitch());
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
			syncDisguise(server, player, session);
			PossessionFaults.boundary("before-start-player-save");
			if (!save(server, player)) { quarantine(player); return; }
			PossessionFaults.boundary("after-start-player-save");
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
		CompoundTag out = new CompoundTag();
		fake.save(out);
		return out;
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

	private static boolean save(MinecraftServer server, ServerPlayer player) {
		if (!PossessionFaults.enabled("fail-player-save")) ((PlayerListAccessor) server.getPlayerList()).players$save(player);
		boolean saved = PossessionPersistence.playerSaved(server, player.getUUID(),
				((PossessionStashHolder) player).players$getSaveToken(), PossessionStashHolder.stashOf(player) != null);
		Constants.traceEvent("possession player save verified {} result={}", player.getUUID(), saved);
		if (!saved) PossessionFaults.boundary("after-failed-player-save");
		return saved;
	}

	private static void quarantine(ServerPlayer player) {
		player.connection.disconnect(Component.literal("Possession could not be saved safely. Please reconnect after the server storage is repaired."));
	}

	private static CompoundTag encodeStash(MinecraftServer server, PlayerStash stash) {
		return (CompoundTag) PlayerStash.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, server.registryAccess()), stash).getOrThrow();
	}

	private static PlayerStash decodeStash(MinecraftServer server, CompoundTag tag) {
		return PlayerStash.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, server.registryAccess()), tag).getOrThrow();
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
				if (player != null && level != null) {
					player.teleportTo(level, t.pos.x, t.pos.y, t.pos.z, Set.of(), t.yaw, t.pitch, true);
					PossessionFaults.boundary("before-release-player-save");
					if (!save(server, player)) quarantine(player);
					PossessionFaults.boundary("after-release-player-save");
				}
			}
			PENDING_TELEPORTS.clear();
		}
		finishOutputs(server);
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
	public static boolean release(ServerPlayer player, Reason reason, @Nullable DamageSource deathSource) {
		MinecraftServer server = player.level().getServer();
		PlayerStash stash = PossessionStashHolder.stashOf(player);
		PossessionSession session = SESSIONS.get(player.getUUID());
		if (server == null || stash == null) {
			if (session != null) SESSIONS.remove(player.getUUID());
			return false;
		}
		if (!RELEASING.add(player.getUUID())) return false;
		try {
			doRelease(server, player, stash, session, reason, deathSource);
			return !SESSIONS.containsKey(player.getUUID()) && PossessionStashHolder.stashOf(player) == null;
		} catch (Throwable t) {
			Constants.LOG.error("Releasing the possession of {} failed", player.getUUID(), t);
			Constants.traceEvent("possession release threw {}: {}", player.getUUID(), t.toString());
			quarantine(player);
			return false;
		} finally {
			RELEASING.remove(player.getUUID());
		}
	}

	private static void doRelease(MinecraftServer server, ServerPlayer player, PlayerStash stash, @Nullable PossessionSession session,
	                              Reason reason, @Nullable DamageSource deathSource) {
		UUID fakeId = stash.fake().uuid();
		PossessionLedger.Entry previous = PossessionLedger.of(server).get(fakeId);
		if (previous != null && !previous.active) {
			// A prepared release owns the output even when a later step threw.
			repair(server, player, previous.stash == null ? stash : decodeStash(server, previous.stash));
			SESSIONS.remove(player.getUUID());
			return;
		}
		Constants.traceEvent("possession release {} fake={} reason={}", player.getUUID(), fakeId, reason.label);
		AIMarkerItem.clearAllFor(player);
		closeAndFlush(player);
		ServerLevel level = player.serverLevel();
		Vec3 pos = player.position();
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		PossessionBodyEntity currentBody = findBody(server, stash.body().uuid());
		if (currentBody != null) {
			stash = new PlayerStash(stash.version(), stash.own(), new PlayerStash.Body(dimensionOf(currentBody.level()),
					currentBody.getX(), currentBody.getY(), currentBody.getZ(), currentBody.getYRot(), currentBody.getXRot(), currentBody.getUUID()), stash.fake());
		} else if (session != null) {
			stash = new PlayerStash(stash.version(), stash.own(), new PlayerStash.Body(session.bodyDimension,
					session.bodyPos.x, session.bodyPos.y, session.bodyPos.z, session.bodyYaw, session.bodyPitch, session.bodyId), stash.fake());
		}

		FakeState state = FakeState.copyFrom(player);
		PossessionLedger ledger = PossessionLedger.of(server);
		PossessionLedger.Entry prepared = prepareOutput(server, ledger, player, stash, level, pos, yaw, pitch, state, reason == Reason.DEATH);
		if (prepared == null) {
			deny(player, "Possession could not be saved; you are still controlling the fake");
			if (reason == Reason.DEATH || reason == Reason.LOGOUT || reason == Reason.SERVER_STOP) quarantine(player);
			return;
		}
		PossessionFaults.boundary("after-release-prepare");
		if (reason == Reason.DEATH && deathSource != null) DEATH_SOURCES.put(fakeId, deathSource);
		FakeState.takeFrom(player);
		removeHealthModifier(player);
		stash.restoreTo(player);
		setGameModeOwn(player, stash.gameMode());
		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		syncHeldSlot(player);
		Constants.traceEvent("possession release {} own state restored, {} stacks go to the fake", player.getUUID(), countStacks(state));

		if (PossessionFaults.enabled("throw-after-own-restore")) throw new IllegalStateException("injected after own restore");
		placeInFake(server, stash.fake().uuid(), prepared);

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
		((PossessionStashHolder) player).players$setSaveToken(prepared.playerToken);
		SESSIONS.remove(player.getUUID());
		if (body != null) body.discard();

		if (reason == Reason.DEATH) {
			PENDING_TELEPORTS.add(new PendingTeleport(player.getUUID(), bodyDim, bodyPos, bodyYaw, bodyPitch));
		} else {
			ServerLevel back = levelOf(server, bodyDim);
			if (back != null) player.teleportTo(back, bodyPos.x, bodyPos.y, bodyPos.z, Set.of(), bodyYaw, bodyPitch, true);
		}
		syncDisguise(server, player, null);
		if (reason != Reason.DEATH) {
			PossessionFaults.boundary("before-release-player-save");
			if (!save(server, player)) quarantine(player);
			PossessionFaults.boundary("after-release-player-save");
		}
		if (reason != Reason.LOGOUT && reason != Reason.SERVER_STOP) {
			COOLDOWN_UNTIL.put(player.getUUID(), (long) server.getTickCount() + PlayersConfig.get().possessionCooldownSeconds * 20L);
		}
		tell(player, stash.fake().name(), reason);
		Constants.traceEvent("possession released {} fake={} reason={}", player.getUUID(), fakeId, reason.label);
	}

	private static PossessionLedger.Entry prepareOutput(MinecraftServer server, PossessionLedger ledger, ServerPlayer player,
			PlayerStash stash, ServerLevel level, Vec3 pos, float yaw, float pitch, FakeState state, boolean death) {
		if (!ledger.available()) return null;
		UUID id = stash.fake().uuid();
		PossessionLedger.Entry previous = ledger.get(id);
		long epoch = Math.max(stash.fake().epoch(), previous == null ? 0 : previous.epoch) + 1;
		try {
			FakePlayerEntity live = find(server, id);
			CompoundTag shell = live == null ? stash.fake().shellSnapshot().copy() : snapshot(live);
			ListTag position = new ListTag();
			position.add(DoubleTag.valueOf(pos.x)); position.add(DoubleTag.valueOf(pos.y)); position.add(DoubleTag.valueOf(pos.z));
			ListTag rotation = new ListTag(); rotation.add(FloatTag.valueOf(yaw)); rotation.add(FloatTag.valueOf(pitch));
			shell.put("Pos", position); shell.put("Rotation", rotation);
			shell.remove("PossessedBy"); shell.putLong("PossessionEpoch", epoch);
			shell.putBoolean("PossessionDeathPending", death);
			PossessionLedger.Entry entry = new PossessionLedger.Entry(epoch, player.getUUID(), false, state.toTag(server.registryAccess()),
					encodeStash(server, stash), shell, dimensionOf(level), death, ledger.nextSequence(), UUID.randomUUID().toString());
			PossessionFaults.boundary("before-release-prepare");
			return ledger.put(id, entry) ? entry : null;
		} catch (RuntimeException e) {
			Constants.LOG.error("Could not prepare possession release", e);
			return null;
		}
	}

	@Nullable
	private static FakePlayerEntity placeInFake(MinecraftServer server, UUID id, PossessionLedger.Entry entry) {
		if (PossessionFaults.enabled("hold-output-materialization")) return null;
		ServerLevel level = levelOf(server, entry.dimension);
		if (level == null || entry.shell == null || entry.pending == null) return null;
		ListTag position = entry.shell.getListOrEmpty("Pos");
		Vec3 pos = new Vec3(position.getDoubleOr(0, 0), position.getDoubleOr(1, 0), position.getDoubleOr(2, 0));
		// Resolve the destination before creating anything: its entity load may contain the committed copy.
		level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(BlockPos.containing(pos)), 2);
		level.getChunk(BlockPos.containing(pos));
		if (!level.areEntitiesLoaded(ChunkPos.asLong(BlockPos.containing(pos)))) return null;
		FakePlayerEntity existing = find(server, id);
		if (existing != null && existing.possessionEpoch() == entry.epoch && !existing.isVacant()) {
			if (existing.level() != level || !existing.chunkPosition().equals(new ChunkPos(BlockPos.containing(pos)))) {
				existing.discardStale();
				existing = null;
			} else {
				return claimOutput(id, existing) ? existing : null;
			}
		}
		if (existing != null) existing.discardStale();
		Entity loaded = EntityType.loadEntityRecursive(entry.shell.copy(), level, EntitySpawnReason.LOAD, entity -> entity);
		if (!(loaded instanceof FakePlayerEntity fake)) throw new IllegalStateException("invalid possession shell");
		FakeState state = FakeState.fromTag(server.registryAccess(), entry.pending).orElseThrow();
		fake.clearVacant(entry.epoch);
		state.applyTo(fake);
		fake.setPossessionDeathPending(entry.death);
		if (entry.death) fake.setHealth(Math.max(1, fake.getHealth()));
		fake.markReconciled();
		if (!level.tryAddFreshEntityWithPassengers(fake)) return null;
		claimOutput(id, fake);
		Constants.traceEvent("possession output materialized fake={} epoch={} death={}", id, entry.epoch, entry.death);
		return fake;
	}

	static boolean claimOutput(UUID id, FakePlayerEntity fake) {
		if (!(fake.level() instanceof ServerLevel level)) return false;
		PossessionLedger.Entry entry = PossessionLedger.of(level.getServer()).get(id);
		if (entry != null && entry.shell != null) {
			ListTag pos = entry.shell.getListOrEmpty("Pos");
			ChunkPos destination = new ChunkPos(BlockPos.containing(pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0)));
			if (!entry.dimension.equals(dimensionOf(level)) || !destination.equals(fake.chunkPosition())) return false;
		}
		FakePlayerEntity old = OUTPUTS.get(id);
		if (old != null && old != fake && !old.isRemoved()) return false;
		OUTPUTS.put(id, fake);
		if (old != fake) Constants.traceEvent("possession output claimed fake={} epoch={} dimension={} chunk={},{}",
				id, fake.possessionEpoch(), dimensionOf(level), fake.chunkPosition().x, fake.chunkPosition().z);
		if (entry != null) entry.markPendingAdopted();
		return true;
	}

	public static boolean outputPending(MinecraftServer server, UUID fake) {
		PossessionLedger ledger = PossessionLedger.of(server);
		if (!ledger.available()) return true;
		PossessionLedger.Entry entry = ledger.get(fake);
		return entry != null && entry.hasOutput();
	}

	private static void finishOutputs(MinecraftServer server) {
		PossessionLedger ledger = PossessionLedger.of(server);
		if (!ledger.available()) return;
		for (Map.Entry<UUID, PossessionLedger.Entry> item : ledger.entries().entrySet()) {
			UUID id = item.getKey();
			PossessionLedger.Entry entry = item.getValue();
			if (!entry.hasOutput() || entry.shell == null) continue;
			if (server.getTickCount() < CHECKPOINT_RETRY.getOrDefault(id, 0)) continue;
			CHECKPOINT_RETRY.put(id, server.getTickCount() + 20);
			try {
				FakePlayerEntity fake = placeInFake(server, id, entry);
				if (fake == null) continue;
				// Both halves must be durable before gameplay can mutate this output.
				if (!PossessionPersistence.playerSaved(server, entry.possessor, entry.playerToken, false)) continue;
				ServerLevel level = (ServerLevel) fake.level();
				PossessionFaults.boundary("before-output-checkpoint");
				level.save(null, true, false);
				var persisted = PossessionPersistence.entitySaved(level, fake.chunkPosition(), id);
				if (persisted.isEmpty() || persisted.get().getLongOr("PossessionEpoch", -1) != entry.epoch
						|| persisted.get().getBooleanOr("PossessionDeathPending", false) != entry.death) continue;
				PossessionFaults.boundary("after-output-readback");
				if (!PossessionFaults.enabled("fail-output-retire") && ledger.put(id, entry.checkpointed())) {
					OUTPUTS.remove(id);
					CHECKPOINT_RETRY.remove(id);
					Constants.traceEvent("possession output checkpointed fake={} epoch={} death={}", id, entry.epoch, entry.death);
					PossessionFaults.boundary("after-output-checkpoint");
				}
			} catch (RuntimeException e) {
				Constants.LOG.error("Possession output {} remains pending", id, e);
			}
		}
	}

	public static void completeDeath(FakePlayerEntity fake, ServerLevel level) {
		PossessionFaults.boundary("before-terminal-death");
		fake.setPossessionDeathPending(false);
		fake.setHealth(0);
		DamageSource source = DEATH_SOURCES.remove(fake.getUUID());
		fake.die(source == null ? level.damageSources().generic() : source);
		PossessionFaults.boundary("after-terminal-death");
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
	public static boolean beforeGameMode(ServerPlayer player, GameType mode) {
		if (ownGameModeChange || !SESSIONS.containsKey(player.getUUID())) return true;
		return release(player, Reason.GAME_MODE, null);
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
		if (!isPossessing(owner)) owner.hurtServer(owner.serverLevel(), source, amount);
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
		AIMarkerItem.clearAllFor(player);
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

	/** The server is gone for good; forget everything that was tied to it. */
	public static void onServerStopped() {
		SESSIONS.clear();
		COOLDOWN_UNTIL.clear();
		PENDING_TELEPORTS.clear();
		RELEASING.clear();
		OUTPUTS.clear();
		CHECKPOINT_RETRY.clear();
		DEATH_SOURCES.clear();
		PossessionLedger.forget();
	}

	// ---- login repair ----

	/** A player who logs in holding a stash was in a fake when the server stopped uncleanly: undo it. */
	public static void onJoin(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null || SESSIONS.containsKey(player.getUUID())) return;
		PossessionLedger ledger = PossessionLedger.of(server);
		if (!ledger.available()) { quarantine(player); return; }
		PlayerStash stash = PossessionStashHolder.stashOf(player);
		var latest = ledger.latestFor(player.getUUID());
		try {
			if (stash == null && latest != null && latest.getValue().stash != null) {
				PossessionLedger.Entry entry = latest.getValue();
				String savedToken = ((PossessionStashHolder) player).players$getSaveToken();
				if (!entry.playerToken.equals(savedToken)) {
					stash = decodeStash(server, entry.stash);
					if (entry.active) {
						// Keep initial vitals out of the ordinary player's lower attribute limits.
						if (prepareInitialOutput(server, ledger, player, stash, entry) == null) {
							throw new IllegalStateException("could not journal uncommitted start recovery");
						}
						PossessionFaults.boundary("after-repair-prepare");
					}
					PossessionStashHolder.setStash(player, stash);
				} else if (entry.active) {
					throw new IllegalStateException("committed possession marker has no stash");
				}
			}
			syncDisguisesTo(server, player);
			if (stash == null) return;
			if (!RELEASING.add(player.getUUID())) return;
			repair(server, player, stash);
		} catch (RuntimeException e) {
			Constants.LOG.error("Possession recovery for {} is quarantined", player.getUUID(), e);
			quarantine(player);
		} finally {
			RELEASING.remove(player.getUUID());
		}
	}

	/** Prepare an uncommitted start without routing its vitals or items through the player's attributes. */
	static PossessionLedger.Entry prepareInitialOutput(MinecraftServer server, PossessionLedger ledger,
			ServerPlayer player, PlayerStash stash, PossessionLedger.Entry entry) {
		ServerLevel destination = levelOf(server, entry.dimension);
		if (destination == null || entry.shell == null || entry.pending == null) throw new IllegalStateException("incomplete start recovery");
		FakeState initial = FakeState.fromTag(server.registryAccess(), entry.pending).orElseThrow();
		ListTag pos = entry.shell.getListOrEmpty("Pos");
		ListTag rotation = entry.shell.getListOrEmpty("Rotation");
		return prepareOutput(server, ledger, player, stash, destination,
				new Vec3(pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0)),
				rotation.getFloatOr(0, 0), rotation.getFloatOr(1, 0), initial, initial.health() <= 0);
	}

	private static void repair(MinecraftServer server, ServerPlayer player, PlayerStash stash) {
		PossessionLedger ledger = PossessionLedger.of(server);
		UUID id = stash.fake().uuid();
		PossessionLedger.Entry entry = ledger.get(id);
		boolean over = entry != null && (!entry.active || entry.epoch > stash.fake().epoch());
		if (!over) {
			FakeState state = FakeState.copyFrom(player);
			entry = prepareOutput(server, ledger, player, stash, player.serverLevel(), player.position(), player.getYRot(), player.getXRot(), state, state.health() <= 0);
			if (entry == null) throw new IllegalStateException("could not journal possession recovery");
			PossessionFaults.boundary("after-repair-prepare");
		}
		FakeState.takeFrom(player);
		removeHealthModifier(player);
		stash.restoreTo(player);
		setGameModeOwn(player, stash.gameMode());
		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		syncHeldSlot(player);
		PossessionBodyEntity body = findBody(server, stash.body().uuid());
		if (body != null) body.discard();
		ServerLevel back = levelOf(server, stash.body().dimension());
		PossessionStashHolder.setStash(player, null);
		((PossessionStashHolder) player).players$setSaveToken(entry.playerToken);
		if (back != null) player.teleportTo(back, stash.body().x(), stash.body().y(), stash.body().z(), Set.of(), stash.body().yaw(), stash.body().pitch(), true);
		syncDisguise(server, player, null);
		if (!save(server, player)) { quarantine(player); return; }
		if (entry.hasOutput()) placeInFake(server, id, entry);
		tell(player, stash.fake().name(), null);
		Constants.traceEvent("possession repair done {} fake={}", player.getUUID(), id);
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

	private static PossessionDisguiseS2C disguiseOf(ServerPlayer player, FakePlayerEntity fake, String fakeName) {
		FakePlayerEntity.SkinData skin = fake.getSkinData();
		return new PossessionDisguiseS2C(player.getUUID(), true, skin.name(), skin.key(), skin.url(), fake.isSlim(), fakeName);
	}

	/**
	 * Tells every client to draw the player as the fake (or as themselves again when {@code session} is null) and refreshes
	 * their tab list entry, whose name comes from the player mixin and whose head from the same disguise.
	 */
	private static void syncDisguise(MinecraftServer server, ServerPlayer player, @Nullable PossessionSession session) {
		PossessionDisguiseS2C packet = session != null ? session.disguise : PossessionDisguiseS2C.off(player.getUUID());
		for (ServerPlayer other : server.getPlayerList().getPlayers()) Network.getNetworkHandler().sendToClient(packet, other);
		server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
				EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), List.of(player)));
		Constants.traceEvent("possession disguise sync {} on={}", player.getUUID(), session != null);
	}

	/** A player who joins mid-possession missed the start, so they are told about every disguise that is live now. */
	private static void syncDisguisesTo(MinecraftServer server, ServerPlayer joiner) {
		for (PossessionSession s : SESSIONS.values()) Network.getNetworkHandler().sendToClient(s.disguise, joiner);
	}
}
