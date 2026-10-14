package dev.duzo.players.entities;

import dev.duzo.players.Constants;
import net.minecraft.core.BlockPos;
import dev.duzo.players.api.CustomBindTracker;
import dev.duzo.players.api.InteractionRegistry;
import dev.duzo.players.api.SkinGrabber;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPItems;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.ai.AIState;
import dev.duzo.players.entities.ai.JobExecutor;
import dev.duzo.players.entities.ai.JobRow;
import dev.duzo.players.entities.ai.JobType;
import dev.duzo.players.entities.ai.requests.SenderAlerts;
import dev.duzo.players.entities.speech.FakeSpeech;
import dev.duzo.players.entities.speech.Verbosity;
import dev.duzo.players.entities.ai.requests.StockKeeper;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.duzo.players.entities.ai.RangedWeapon;
import dev.duzo.players.entities.goal.FakeMeleeAttackGoal;
import dev.duzo.players.entities.goal.FakeRangedAttackGoal;
import dev.duzo.players.entities.goal.FollowOwnerGoal;
import dev.duzo.players.entities.goal.IdleLookAtPlayerGoal;
import dev.duzo.players.entities.goal.SleepInBedGoal;
import dev.duzo.players.entities.goal.WatchOwnerGoal;
import dev.duzo.players.entities.goal.JobAwareTemptGoal;
import dev.duzo.players.entities.goal.HumanoidWaterAvoidingRandomStrollGoal;
import dev.duzo.players.entities.goal.MoveTowardsItemsGoal;
import dev.duzo.players.entities.inventory.FakePlayerInventory;
import dev.duzo.players.entities.possession.PossessedLookControl;
import dev.duzo.players.entities.possession.PossessedMoveControl;
import dev.duzo.players.entities.possession.PossessionView;
import dev.duzo.players.menu.FakeCrafterMenu;
import dev.duzo.players.menu.FakePlayerMenu;
import dev.duzo.players.menu.FakePlayerMenuProvider;
import dev.duzo.players.platform.Services;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import dev.duzo.players.entities.ai.FakeHurtByTargetGoal;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundEvents;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import javax.annotation.Nullable;
import java.util.function.Predicate;

public class FakePlayerEntity extends PathfinderMob implements CrossbowAttackMob {
	private static final EntityDataAccessor<Integer> PHYSICAL_STATE = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<CompoundTag> SKIN_DATA = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.COMPOUND_TAG);
	private static final EntityDataAccessor<Boolean> SLIM = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<CompoundTag> AI_STATE = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.COMPOUND_TAG);
	// client-visual-only "what the job is placing" item, drawn in the main hand by the renderer;
	// never affects the real MAINHAND equipment slot and does not need to survive a reload
	private static final EntityDataAccessor<ItemStack> DISPLAY_ITEM = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.ITEM_STACK);
	private static final EntityDataAccessor<Byte> STATUS = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.BYTE);
	/** {@code "<gameTime>|<text>"} of the last public line. Not persisted: a bubble is for the moment it was said. */
	private static final EntityDataAccessor<String> SPEECH = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.STRING);
	// drives the client-side crossbow charging pose; combat state only, never persisted
	private static final EntityDataAccessor<Boolean> CHARGING_CROSSBOW = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.BOOLEAN);
	/** Entity id of the player possessing this fake, or -1. Never persisted: ids do not survive a reload, and a possession cannot either. */
	private static final EntityDataAccessor<Integer> POSSESSOR = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.INT);
	private SkinData dataCache;
	private AIState aiCache;
	private Component nameCache;
	private final FakePlayerInventory inventory = new FakePlayerInventory(this);
	private JobExecutor jobExecutor;
	private ResourceLocation jobExecutorJob = FPJobs.NONE_ID;
	private boolean jobExecutorBound;
	// cached with the executor, so the stock check is not a registry lookup per fake per tick
	private boolean jobKeepsStock;
	// counts down rather than matching a tick phase, so a fake that stops ticking for a while
	// resumes its checks instead of skipping every period whose tick it happened to miss
	private int stockCountdown;
	private boolean jobPaused;
	private boolean jobActivePrev;

	public FakePlayerEntity(EntityType<? extends FakePlayerEntity> type, Level level) {
		super(type, level);
		// vanilla's DropChances.DEFAULT only drops equipment ~8.5% of the time; the fake's gear should
		// never be destroyed by death or lava just because it wasn't recently hit by a player.
		for (EquipmentSlot slot : EquipmentSlot.values()) this.setGuaranteedDrop(slot);
		this.moveControl = new PossessedMoveControl(this);
		this.lookControl = new PossessedLookControl(this);
	}

	public FakePlayerEntity(Level level) {
		this(FPEntities.FAKE_PLAYER.get(), level);
	}

	private boolean chunkAnchored;
	private final LongSet forcedChunks = new LongOpenHashSet();

	public boolean isChunkAnchored() {
		return chunkAnchored;
	}

	public void setChunkAnchored(boolean anchored) {
		this.chunkAnchored = anchored;
	}

	/** Chunks this fake force-loaded and so is responsible for releasing. Mutated by {@link ChunkAnchors}. */
	public LongSet forcedChunks() {
		return forcedChunks;
	}

	@Override
	public void tick() {
		super.tick();
		this.updateSwingTime();
		if (this.level() instanceof ServerLevel server) {
			if (chunkAnchored && this.isAlive()) ChunkAnchors.update(server, this);
			this.updateFollowOverridePause();
			this.reconcileBed(server);
			this.tickRiding();
			this.tickJobExecutor(server);
		}
	}

	// ---- riding ----

	private static final int ACCIDENTAL_SEAT_GRACE = 20;
	private static final int UNSEATED_GRACE = 20;
	/** Seated on purpose (marker or Create seat): the job waits for it instead of the fake getting off. Saved. */
	private boolean orderedSeat;
	private int accidentalSeatTicks;
	private int unseatedTicks;
	// only a moving contraption (a train carriage) takes its passengers out of loaded chunks and
	// discards them; a fake on a plain create:seat that gets discarded is really gone
	private static boolean isCreateContraption(Entity vehicle) {
		var id = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
		return "create".equals(id.getNamespace()) && id.getPath().contains("contraption");
	}

	private boolean carriedOffByCreate;

	/** Seated in a boat, minecart, seat or anything else that carries passengers. */
	public boolean isRiding() {
		return this.isPassenger();
	}

	public boolean isOrderedSeat() {
		return this.orderedSeat;
	}

	public void setOrderedSeat(boolean ordered) {
		this.orderedSeat = ordered;
		this.unseatedTicks = 0;
	}

	private void tickRiding() {
		carriedOffByCreate = false;
		if (!this.isPassenger()) {
			accidentalSeatTicks = 0;
			// a seat that re-seats its riders (assembling a train) leaves a gap, so the order is only forgotten after a while
			if (orderedSeat && ++unseatedTicks > UNSEATED_GRACE) {
				orderedSeat = false;
				unseatedTicks = 0;
				Constants.traceEvent("seat order lapsed {}", this.getUUID());
			}
			return;
		}
		unseatedTicks = 0;
		if (this.getPhysicalState() == PhysicalState.LAYING) this.setPhysicalState(PhysicalState.STANDING);
		if (orderedSeat || !this.isMovementManagedByJob()) {
			accidentalSeatTicks = 0;
			return;
		}
		if (++accidentalSeatTicks > ACCIDENTAL_SEAT_GRACE) {
			Constants.traceEvent("seat accidental dismount {} from {}", this.getUUID(), this.getVehicle());
			accidentalSeatTicks = 0;
			this.stopRiding();
		}
	}

	@Override
	public void removeVehicle() {
		Entity vehicle = this.getVehicle();
		if (vehicle != null && (this.getRemovalReason() == RemovalReason.DISCARDED || vehicle.getRemovalReason() == RemovalReason.DISCARDED)
				&& isCreateContraption(vehicle)) {
			this.carriedOffByCreate = true;
		}
		super.removeVehicle();
	}

	// setRemoved lets go of the vehicle before onRemoval runs, so "was riding" has to be remembered from removeVehicle
	private boolean discardedAsPassenger(RemovalReason reason) {
		return reason == RemovalReason.DISCARDED && this.carriedOffByCreate;
	}

	// ---- possession ----

	/** Hooks the client fills in so a possessed fake looks where its possessor's mouse does; null on a dedicated server. */
	@Nullable
	private static PossessionView viewHook;

	public static void setViewHook(@Nullable PossessionView hook) {
		viewHook = hook;
	}

	/** Entity id of the possessing player, or -1. Synced, so the possessing client can recognise its own fake. */
	public int getPossessor() {
		return this.entityData.get(POSSESSOR);
	}

	public boolean isPossessed() {
		return this.entityData.get(POSSESSOR) >= 0;
	}

	/** Server side. Takes or releases control of this fake; use {@code PossessionManager}, which pairs it with the player. */
	public void setPossessor(int playerId) {
		this.entityData.set(POSSESSOR, playerId);
		this.setJobPaused(this.isPossessed());
		this.getNavigation().stop();
		// goals holding MOVE, LOOK or JUMP must stop now, not at the next 5-tick control flag refresh
		this.updateControlFlags();
		this.setJumping(false);
		this.setZza(0.0F);
		this.setXxa(0.0F);
		if (playerId >= 0) {
			this.setTarget(null);
			this.wakeForPossession();
		}
	}

	/** A possessed fake cannot be asleep or lying down, as its player has to be able to walk it. */
	private void wakeForPossession() {
		this.wakeFromBed();
		if (this.getPhysicalState() != PhysicalState.STANDING) this.setPhysicalState(PhysicalState.STANDING);
	}

	/** Server side. Faces where the possessor looks; the controls only pass this through while possessed. */
	public void setPossessedLook(float yaw, float pitch) {
		this.setYRot(yaw);
		this.setXRot(pitch);
		this.setYHeadRot(yaw);
	}

	/** Server side. Held movement of the possessor: forward and strafe in -1..1. */
	public void setPossessedMove(float forward, float strafe, boolean jump) {
		if (this.moveControl instanceof PossessedMoveControl control) control.setInput(forward, strafe, jump);
	}

	@Override
	protected void updateControlFlags() {
		super.updateControlFlags();
		if (this.isPossessed()) {
			this.goalSelector.setControlFlag(Goal.Flag.MOVE, false);
			this.goalSelector.setControlFlag(Goal.Flag.JUMP, false);
			this.goalSelector.setControlFlag(Goal.Flag.LOOK, false);
		}
	}

	@Override
	public float getViewYRot(float partialTick) {
		PossessionView hook = viewHook;
		if (hook != null && this.isPossessed()) {
			float yaw = hook.viewYRot(this, partialTick);
			if (!Float.isNaN(yaw)) return yaw;
		}
		return super.getViewYRot(partialTick);
	}

	@Override
	public float getViewXRot(float partialTick) {
		PossessionView hook = viewHook;
		if (hook != null && this.isPossessed()) {
			float pitch = hook.viewXRot(this, partialTick);
			if (!Float.isNaN(pitch)) return pitch;
		}
		return super.getViewXRot(partialTick);
	}

	// ---- bed ----

	@Nullable
	private BlockPos bedClaim;
	private String bedClaimDim = "";
	private boolean bedReconciled;
	private boolean wokenByDamage;

	@Nullable
	public BlockPos bedClaim() {
		return this.bedClaim;
	}

	public void setBedClaim(BlockPos pos) {
		this.bedClaim = pos.immutable();
		this.bedClaimDim = this.level().dimension().location().toString();
	}

	/** True once, if the last bed sleep ended because the fake took damage. Reading it clears it. */
	public boolean consumeWokenByDamage() {
		boolean was = this.wokenByDamage;
		this.wokenByDamage = false;
		return was;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		boolean asleep = this.isBedSleeping();
		if (asleep) this.wokenByDamage = true;
		boolean hurt = super.hurtServer(level, source, amount);
		if (asleep && !hurt) this.wokenByDamage = false;
		return hurt;
	}

	/** Asleep in a real bed, as opposed to the LAYING pose, which {@link #isSleeping()} also reports. */
	public boolean isBedSleeping() {
		return this.getSleepingPos().isPresent();
	}

	/** Gives the bed's POI ticket back. Safe to call with no claim, twice, or after the bed has gone. */
	public void releaseBedClaim() {
		BlockPos pos = this.bedClaim;
		if (pos == null) return;
		this.bedClaim = null;
		if (this.level() instanceof ServerLevel server) {
			try {
				server.getPoiManager().release(pos);
			} catch (IllegalStateException e) {
				Constants.traceEvent("bed release failed {} at {}: {}", this.getUUID(), pos, e.getMessage());
			}
			Constants.traceEvent("bed release {} at {}", this.getUUID(), pos);
		}
	}

	private void wakeFromBed() {
		if (this.isBedSleeping()) this.stopSleeping();
		this.releaseBedClaim();
	}

	/**
	 * After a reload the claim and sleeping position are both persisted. Keep them only as a matched pair (the goal then
	 * resumes), and drop whichever is left over so neither a ticket nor a bed-bound fake leaks. Also wakes a sleeper
	 * that has been given something to do even if the goal is not running.
	 */
	private void reconcileBed(ServerLevel server) {
		if (!this.bedReconciled) {
			this.bedReconciled = true;
			BlockPos claim = this.bedClaim;
			boolean paired = claim != null && this.getSleepingPos().filter(claim::equals).isPresent();
			if (!paired) {
				if (this.isBedSleeping()) this.stopSleeping();
				this.releaseBedClaim();
			}
		}
		if (this.bedClaim != null && this.isBedSleeping() && (!this.isIdleNow() || !SleepInBedGoal.dark(server, this))) {
			this.wakeFromBed();
		}
	}

	// Pause the running job while the owner is holding a bound marker or has this fake's menu open,
	// so the follow-override goal can take over movement without losing the job.
	private void updateFollowOverridePause() {
		setJobPaused(getFollowOverrideOwner() != null);
	}

	private void tickJobExecutor(ServerLevel level) {
		AIState state = this.getAIState();
		ResourceLocation job = state.jobId();
		if (!jobExecutorBound || !job.equals(jobExecutorJob)) {
			JobType type = FPJobs.get(job);
			// an unresolved job leaves the executor null rather than installing a noop, because a
			// noop serializes an empty tag and flushJobState would overwrite the real JobState on
			// the next save. flushJobState returns early on null, so an absent mod's job survives.
			jobExecutor = type == null ? null : type.createExecutor();
			if (jobExecutor != null) jobExecutor.deserialize(state.jobState());
			jobKeepsStock = type != null && type.rows().contains(JobRow.STOCK);
			jobGraceTicks = type == null ? 0 : type.progressGraceSeconds() * 20;
			// staggered by entity, so a base of fakes bound on the same tick does not run every
			// quartermaster scan on the same tick for ever after
			stockCountdown = 1 + Math.floorMod(this.getUUID().hashCode(), StockKeeper.checkTicks());
			jobExecutorJob = job;
			jobExecutorBound = true;
			jobActivePrev = false;
			resetProblem();
		}
		if (jobExecutor == null) return;
		boolean active = state.running() && !jobPaused && !(orderedSeat && this.isPassenger());
		if (active != jobActivePrev) {
			if (active) jobExecutor.onResume(this);
			else jobExecutor.onPause(this);
			jobActivePrev = active;
			resetProblem();
		}
		if (active && !this.isRiding()) {
			// before the job runs, and outside the executor, so a job keeps stock by declaring the
			// row rather than by calling this itself. It never blocks: it raises and returns.
			if (jobKeepsStock && --stockCountdown <= 0) {
				stockCountdown = StockKeeper.checkTicks();
				StockKeeper.tick(level, this);
			}
			jobExecutor.tick(level, this);
			this.checkStuck(level);
		}
	}

	/**
	 * Nothing to do and nothing wrong: no job (or the idle job, or a job that is not running), not paused for the
	 * owner, no problem mark, not fighting, not lying down. Reactions are gated on this so a working fake is never
	 * turned away from what it is doing.
	 */
	public boolean isIdleNow() {
		if (this.isPossessed() || this.jobPaused || this.getProblem() != FakeProblem.NONE || this.getTarget() != null) return false;
		if (this.getPhysicalState() == PhysicalState.LAYING) return false;
		AIState state = this.getAIState();
		return !state.running() || FPJobs.NONE_ID.equals(state.jobId()) || FPJobs.is(state.jobId(), FPJobs.IDLE);
	}

	public void setJobPaused(boolean paused) {
		this.jobPaused = paused || this.isPossessed();
	}

	public boolean isJobPaused() {
		return this.jobPaused;
	}

	/** The item a job wants drawn in the main hand right now, or empty to show the real held item. */
	public ItemStack getDisplayItem() {
		return this.entityData.get(DISPLAY_ITEM);
	}

	public void setDisplayItem(ItemStack stack) {
		this.entityData.set(DISPLAY_ITEM, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
	}

	private static final double FOLLOW_OWNER_RANGE_SQ = 32.0D * 32.0D;

	@Nullable
	private Player resolveOwnerInRange() {
		AIState state = this.getAIState();
		if (state.ownerUUID() == null) return null;
		Player owner = this.level().getPlayerByUUID(state.ownerUUID());
		if (owner == null || !owner.isAlive() || owner.level() != this.level()) return null;
		return this.distanceToSqr(owner) <= FOLLOW_OWNER_RANGE_SQ ? owner : null;
	}

	// Owner the fake should walk to right now: either the Follow job, or a follow-override
	// (owner holding a marker bound to this fake, or viewing this fake's menu).
	@Nullable
	public Player getFollowTarget() {
		Player owner = resolveOwnerInRange();
		if (owner == null) return null;
		AIState state = this.getAIState();
		boolean jobFollow = state.running() && FPJobs.is(state.jobId(), FPJobs.FOLLOW);
		return (jobFollow || isFollowOverride(owner)) ? owner : null;
	}

	@Nullable
	private Player getFollowOverrideOwner() {
		Player owner = resolveOwnerInRange();
		return (owner != null && isFollowOverride(owner)) ? owner : null;
	}

	// Follow-override: while the owner holds a marker bound to this fake or has its menu open,
	// the fake follows them without changing its job.
	private boolean isFollowOverride(Player owner) {
		return holdsBoundMarker(owner) || hasMenuOpen(owner);
	}

	private boolean holdsBoundMarker(Player owner) {
		return isBoundMarker(owner.getMainHandItem()) || isBoundMarker(owner.getOffhandItem());
	}

	private boolean isBoundMarker(ItemStack stack) {
		return stack.getItem() instanceof AIMarkerItem && this.getUUID().equals(AIMarkerItem.fakeUUID(stack));
	}

	private boolean hasMenuOpen(Player owner) {
		return (owner.containerMenu instanceof FakePlayerMenu menu && menu.getEntity() == this)
				|| (owner.containerMenu instanceof FakeCrafterMenu craft && craft.fake() == this);
	}

	public boolean isMovementManagedByJob() {
		AIState state = this.getAIState();
		if (!state.running() || jobPaused) return false;
		return !FPJobs.NONE_ID.equals(state.jobId());
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return super.mobInteract(player, hand);
		}

		if (!player.level().isClientSide() && this.bedClaim != null) this.wakeFromBed();

		// a client that has bound the open-menu key opens the menu with that instead, leaving right-click
		// free for other mods that claim it
		if (player.isShiftKeyDown() && !CustomBindTracker.hasCustomBind(player)) {
			if (!player.level().isClientSide()) {
				Services.COMMON_REGISTRY.openMenu(
						(ServerPlayer) player,
						new FakePlayerMenuProvider(this),
						buf -> buf.writeInt(this.getId())
				);
			}
			return InteractionResult.SUCCESS;
		}

		if (!player.level().isClientSide()) {
			return InteractionRegistry.INSTANCE.get(player.getItemInHand(hand).getItem()).run((ServerPlayer) player, this);
		}

		return super.mobInteract(player, hand);
	}

	// ---- ranged combat ----

	/** The hand holding a ranged weapon this fake can actually fight with right now, or null. */
	@Nullable
	public InteractionHand getUsableRangedHand() {
		// both hands, so an empty bow in the main hand does not hide a usable trident in the offhand
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack weapon = this.getItemInHand(hand);
			RangedWeapon kind = RangedWeapon.of(weapon);
			if (kind != null && this.hasAmmoFor(kind, weapon)) return hand;
		}
		return null;
	}

	@Nullable
	public RangedWeapon getUsableRangedWeapon() {
		InteractionHand hand = this.getUsableRangedHand();
		return hand == null ? null : RangedWeapon.of(this.getItemInHand(hand));
	}

	private boolean hasAmmoFor(RangedWeapon kind, ItemStack weapon) {
		if (kind == RangedWeapon.TRIDENT) return true;
		// a loaded crossbow already holds its bolt: vanilla moves the arrow out of the inventory when charging
		// completes, tens of ticks before the shot, so requiring inventory ammo here stranded the last arrow
		if (kind == RangedWeapon.CROSSBOW && CrossbowItem.isCharged(weapon)) return true;
		return !this.getProjectile(weapon).isEmpty();
	}

	/** Whether a combat goal may drive movement, or a working job is actively pathing and must not be fought. */
	public boolean allowsCombatMovement() {
		// the Guard job yields navigation to its target, so combat is free to take over there
		return !this.isMovementManagedByJob() || FPJobs.is(this.getAIState().jobId(), FPJobs.GUARD);
	}

	// Ammunition comes out of the fake's own inventory, so it runs dry like a player rather than
	// shooting for free like a skeleton. Hands are checked first so an offhand quiver still works.
	@Override
	public ItemStack getProjectile(ItemStack weapon) {
		Predicate<ItemStack> accepts = RangedWeapon.ammo(weapon);

		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack held = this.getItemInHand(hand);
			if (!held.isEmpty() && accepts.test(held)) return held;
		}

		for (int slot = 0; slot < this.inventory.getContainerSize(); slot++) {
			ItemStack stack = this.inventory.getItem(slot);
			if (!stack.isEmpty() && accepts.test(stack)) return stack;
		}

		return ItemStack.EMPTY;
	}

	@Override
	public void performRangedAttack(LivingEntity target, float velocity) {
		if (!(this.level() instanceof ServerLevel server)) return;

		InteractionHand hand = this.getUsableRangedHand();
		if (hand == null) return;

		ItemStack weapon = this.getItemInHand(hand);
		RangedWeapon kind = RangedWeapon.of(weapon);
		if (kind == null) return;

		if (kind == RangedWeapon.TRIDENT) {
			this.throwTrident(server, target, weapon, hand);
			return;
		}

		// the goal fires crossbows through performCrossbowAttack; only reachable if something else asks
		if (kind == RangedWeapon.CROSSBOW) {
			this.performCrossbowAttack(this, velocity);
			return;
		}

		ItemStack ammo = this.getProjectile(weapon);
		if (ammo.isEmpty()) return;

		AbstractArrow arrow = ProjectileUtil.getMobArrow(this, ammo.copyWithCount(1), velocity, weapon);
		// the fake paid for this arrow out of its inventory, so let it be picked back up
		arrow.pickup = AbstractArrow.Pickup.ALLOWED;
		this.shootAt(server, target, arrow, ammo);
		ammo.shrink(1);
		this.inventory.setChanged();
		weapon.hurtAndBreak(1, this, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
	}

	// A thrown trident is not recoverable, so the held one is only damaged rather than consumed. Making the
	// copy pickup-able instead would hand the thrower a second trident every throw.
	private void throwTrident(ServerLevel server, LivingEntity target, ItemStack weapon, InteractionHand hand) {
		ThrownTrident trident = new ThrownTrident(server, this, weapon.copyWithCount(1));
		trident.pickup = AbstractArrow.Pickup.DISALLOWED;
		this.shootAt(server, target, trident, weapon);
		weapon.hurtAndBreak(1, this, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		this.playSound(SoundEvents.DROWNED_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
	}

	// Same lob and same difficulty-scaled inaccuracy vanilla's skeleton uses.
	private void shootAt(ServerLevel server, LivingEntity target, AbstractArrow projectile, ItemStack from) {
		double dx = target.getX() - this.getX();
		double dy = target.getY(0.3333333333333333D) - projectile.getY();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		Projectile.spawnProjectileUsingShoot(projectile, server, from, dx, dy + horizontal * 0.20000000298023224D, dz,
				1.6F, 14 - server.getDifficulty().getId() * 4);
	}

	@Override
	public void setChargingCrossbow(boolean charging) {
		this.entityData.set(CHARGING_CROSSBOW, charging);
	}

	public boolean isChargingCrossbow() {
		return this.entityData.get(CHARGING_CROSSBOW);
	}

	@Override
	public void onCrossbowAttackPerformed() {
		this.noActionTime = 0;
	}

	public static AttributeSupplier.Builder getHumanoidAttributes() {
		PlayersConfig config = PlayersConfig.get();
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, config.maxHealth)
				.add(Attributes.MOVEMENT_SPEED, config.movementSpeed)
				.add(Attributes.ATTACK_DAMAGE, config.attackDamage)
				.add(Attributes.TEMPT_RANGE)
				.add(Attributes.FOLLOW_RANGE, Math.max(16.0, Math.min(2048.0, config.pathRange)));
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(7, new HumanoidWaterAvoidingRandomStrollGoal(this, 1.0D));
		this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
		// both only run while idle, see isIdleNow; the owner is watched before a stranger, and both before random glances
		this.goalSelector.addGoal(5, new WatchOwnerGoal(this));
		this.goalSelector.addGoal(2, new SleepInBedGoal(this));
		this.goalSelector.addGoal(6, new IdleLookAtPlayerGoal(this, 6.0F));
		this.goalSelector.addGoal(5, new OpenDoorGoal(this, true));
		this.targetSelector.addGoal(1, new FakeHurtByTargetGoal(this));
		this.goalSelector.addGoal(3, new FakeMeleeAttackGoal(this, 1.5D, true));
		this.goalSelector.addGoal(2, new MoveTowardsItemsGoal(this, 1.0D, true));
		// above the item-pickup goal on purpose: goals only yield the movement flag to a strictly better
		// priority, so at 2 a fake already walking to a drop could never break off to shoot
		this.goalSelector.addGoal(1, new FakeRangedAttackGoal(this, 1.0D, 15.0F));
		this.goalSelector.addGoal(1, new JobAwareTemptGoal(this, 1.0D, Ingredient.of(Items.REDSTONE_BLOCK, Items.REDSTONE_TORCH), false));
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(0, new FollowOwnerGoal(this));
	}

	@Override
	protected PathNavigation createNavigation(Level level) {
		GroundPathNavigation navigator = new GroundPathNavigation(this, level);
		navigator.setCanFloat(true);
		navigator.setCanOpenDoors(true);
		return navigator;
	}

	@Override
	public void addAdditionalSaveData(CompoundTag nbt) {
		super.addAdditionalSaveData(nbt);

		nbt.putInt("State", this.getPhysicalState().ordinal());
		nbt.put("SkinData", this.entityData.get(SKIN_DATA));
		nbt.putBoolean("Slim", this.isSlim());
		this.flushJobState();
		nbt.put("AIState", this.entityData.get(AI_STATE));
		if (chunkAnchored) nbt.putBoolean("ChunkAnchor", true);
		if (orderedSeat) nbt.putBoolean("OrderedSeat", true);
		if (bedClaim != null) {
			nbt.put("BedClaim", NbtUtils.writeBlockPos(bedClaim));
			nbt.putString("BedClaimDim", bedClaimDim);
		}
		if (!forcedChunks.isEmpty()) {
			nbt.putLongArray("ForcedChunks", forcedChunks.toLongArray());
			nbt.putString("ForcedDim", this.level().dimension().location().toString());
		}
		nbt.put("Inventory", this.inventory.createTag(this.registryAccess()));
	}

	/**
	 * The live executor, or null if this fake has not ticked its job yet. Never use this to decide
	 * whether another fake is alive or assigned: it is null for a whole tick after a reload and
	 * entity tick order is arbitrary. Read AIState for that.
	 */
	@Nullable
	public JobExecutor activeJobExecutor() {
		return this.jobExecutor;
	}

	/**
	 * Runs from setRemoved, so it covers every removal path including teleportCrossDimension, which
	 * bypasses remove(). On CHANGED_DIMENSION level() is still the old level, so anything keyed on it
	 * is released in the right place.
	 */
	@Override
	public void onRemoval(RemovalReason reason) {
		if (this.level() instanceof ServerLevel server) {
			// a seat leaving loaded chunks (a Create train) discards its riders, but they are only away, not gone
			boolean unload = reason == RemovalReason.UNLOADED_TO_CHUNK || this.discardedAsPassenger(reason);
			// an unload keeps the claim, which is saved and reconciled on the next load; anything else frees the bed
			if (!unload) this.releaseBedClaim();
			if (unload) {
				// chunks stay forced so the fake loads again after a restart
				ChunkAnchors.unloaded(this);
			} else {
				ChunkAnchors.release(server, this);
			}
			// bounded cache: a rebuilt index costs one rescan, so only drop it when the fake is gone or left the level
			if (!unload && (reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION)) {
				dev.duzo.players.entities.ai.requests.PoolIndex.forget(server, this.getUUID());
			}
			// server thread only, and only when the fake is really gone
			if (!unload && reason.shouldDestroy()) {
				dev.duzo.players.entities.ai.requests.SenderAlerts.forget(this.getUUID());
				dev.duzo.players.entities.ai.claims.WorkClaims.releaseAll(this.getUUID());
			}
		}
		super.onRemoval(reason);
	}

	public void flushJobState() {
		if (jobExecutor == null) return;
		CompoundTag tag = jobExecutor.serialize();
		final CompoundTag finalTag = tag == null ? new CompoundTag() : tag;
		this.mutateAIState(s -> s.setJobState(finalTag));
	}

	public void resetJobExecutor() {
		// run the pause path before discarding the executor - otherwise stopping the job never fires
		// onPause and any cleanup it does never happens. Flush after onPause runs so the persisted
		// state matches what onPause just left behind, not the stale mid-job state.
		if (this.jobExecutor != null) {
			this.jobExecutor.onPause(this);
			this.flushJobState();
		}
		this.jobExecutor = null;
		this.jobExecutorBound = false;
		this.jobExecutorJob = FPJobs.NONE_ID;
		this.jobKeepsStock = false;
		this.jobActivePrev = false;
	}

	@Override
	public void readAdditionalSaveData(CompoundTag nbt) {
		super.readAdditionalSaveData(nbt);

		// super's read unconditionally replaces dropChances with the default (~8.5%) when the save
		// predates guaranteed drops, and addAdditionalSaveData only writes the key when non-default,
		// so it never self-heals; re-apply the guarantee every load instead.
		for (EquipmentSlot slot : EquipmentSlot.values()) this.setGuaranteedDrop(slot);

		this.bedClaim = NbtUtils.readBlockPos(nbt, "BedClaim").orElse(null);
		this.bedClaimDim = nbt.getString("BedClaimDim");
		// a claim copied across by a dimension change names a bed in the other dimension; the original releases it
		if (this.bedClaim != null && !this.bedClaimDim.equals(this.level().dimension().location().toString())) {
			this.bedClaim = null;
		}
		this.bedReconciled = false;
		this.chunkAnchored = nbt.getBoolean("ChunkAnchor");
		this.orderedSeat = nbt.getBoolean("OrderedSeat");
		this.forcedChunks.clear();
		// a list recorded in another dimension (copied across by a dimension change) is not ours to unforce here
		if (nbt.getString("ForcedDim").equals(this.level().dimension().location().toString())) {
			for (long chunk : nbt.getLongArray("ForcedChunks")) this.forcedChunks.add(chunk);
		}
		this.dataCache = null;
		this.aiCache = null;
		this.nameCache = null;
		this.entityData.set(PHYSICAL_STATE, nbt.getInt("State"));
		this.entityData.set(SKIN_DATA, nbt.getCompound("SkinData"));
		this.entityData.set(SLIM, nbt.getBoolean("Slim"));
		if (nbt.contains("AIState")) this.entityData.set(AI_STATE, nbt.getCompound("AIState"));
		if (nbt.contains("Inventory", Tag.TAG_LIST)) {
			this.inventory.fromTag(nbt.getList("Inventory", Tag.TAG_COMPOUND), this.registryAccess());
		}
	}

	public FakePlayerInventory getInventory() {
		return inventory;
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
		super.onSyncedDataUpdated(data);

		if (SKIN_DATA.equals(data)) {
			this.dataCache = null;
			this.nameCache = null;
		} else if (AI_STATE.equals(data)) {
			this.aiCache = null;
		} else if (SPEECH.equals(data) && this.level().isClientSide()) {
			this.bufferSpeech(this.entityData.get(SPEECH));
		}
	}

	private static final int SPEECH_BUFFER = 8;
	/** Synced to every tracker, so a long line is cut rather than sent whole. */
	private static final int SPEECH_MAX_CHARS = 256;
	// a line this old when it arrives is the initial sync of a fake that spoke long ago, not news
	private static final long SPEECH_STALE_TICKS = 100L;

	private final java.util.ArrayDeque<SpeechLine> speechLines = new java.util.ArrayDeque<>();

	/** A line as the client saw it arrive, for the bubble renderer. Plain data: no client classes here. */
	public record SpeechLine(long tick, String text) {}

	/** Client side only. Oldest first. The renderer owns expiry. */
	public java.util.Deque<SpeechLine> speechLines() {
		return this.speechLines;
	}

	private void bufferSpeech(String raw) {
		int bar = raw.indexOf('|');
		if (bar < 0) return;
		long now = this.level().getGameTime();
		try {
			if (now - Long.parseLong(raw.substring(0, bar)) > SPEECH_STALE_TICKS) return;
		} catch (NumberFormatException e) {
			return;
		}
		String text = raw.substring(bar + 1);
		if (text.isEmpty()) return;
		this.speechLines.addLast(new SpeechLine(now, text));
		while (this.speechLines.size() > SPEECH_BUFFER) this.speechLines.removeFirst();
	}

	/** The problem mark to draw, synced. Never persisted: a job that is still blocked after a reload reports it again. */
	public FakeProblem getProblem() {
		return FakeProblem.byId(this.entityData.get(STATUS));
	}

	/** Server side: something is wrong with the running job. Replaces any earlier problem. */
	public void reportProblem(FakeProblem problem) {
		if (this.level().isClientSide()) return;
		FakeProblem old = this.getProblem();
		if (old == problem) return;
		this.entityData.set(STATUS, (byte) problem.ordinal());
		if (problem == FakeProblem.NONE) this.lastProgressTick = this.level().getGameTime();
		Constants.traceEvent("status {} {} -> {}", this.getUUID(), old, problem);
	}

	/** Server side: the job it was blocked on is moving again. Leaves a stuck mark alone, which only progress clears. */
	public void clearProblem() {
		FakeProblem old = this.getProblem();
		if (old != FakeProblem.NONE && old != FakeProblem.STUCK) this.reportProblem(FakeProblem.NONE);
	}

	/** Server side: the job was stopped, paused or replaced, so no problem carries over and the stuck timer restarts. */
	private void resetProblem() {
		this.reportProblem(FakeProblem.NONE);
		this.restartProgressTimer();
	}

	private long lastProgressTick;
	private long lastCheckTick;
	private Vec3 lastProgressPos = Vec3.ZERO;
	private int jobGraceTicks;

	private void restartProgressTimer() {
		this.lastProgressTick = this.level().getGameTime();
		this.lastProgressPos = this.position();
	}

	/**
	 * Server side: the running job just did something real (mined, placed, moved items, crafted, caught, delivered).
	 * Restarts the stuck timer and clears any problem mark, which that progress proves is over.
	 */
	public void markProgress() {
		if (this.level().isClientSide()) return;
		this.restartProgressTimer();
		if (this.getProblem() == FakeProblem.STUCK) {
			FakeSpeech.clear(this, "stuck");
			SenderAlerts.clear(this, "stuck");
		}
		this.reportProblem(FakeProblem.NONE);
	}

	/** Server side: the job is legitimately waiting for work (empty source, crops growing), so that is not being stuck. */
	public void markIdle() {
		if (this.level().isClientSide()) return;
		this.restartProgressTimer();
		if (this.getProblem() == FakeProblem.STUCK) {
			FakeSpeech.clear(this, "stuck");
			SenderAlerts.clear(this, "stuck");
			this.reportProblem(FakeProblem.NONE);
		}
	}

	/**
	 * Flags a job that is meant to be working but has not progressed for its grace period. A fake that is
	 * travelling is not counted as stuck, since a long walk back to a chest is work no job reports as progress.
	 */
	private void checkStuck(ServerLevel level) {
		if (this.jobGraceTicks <= 0 || this.getProblem() != FakeProblem.NONE) return;
		long now = level.getGameTime();
		long sinceCheck = now - this.lastCheckTick;
		this.lastCheckTick = now;
		if (sinceCheck > 1L) this.lastProgressTick = now; // not ticked for a while: that time was not spent stuck
		if (this.position().distanceToSqr(this.lastProgressPos) >= TRAVEL_COUNTS_SQ) {
			this.lastProgressPos = this.position();
			this.lastProgressTick = now;
			return;
		}
		if (now < this.lastProgressTick) { // a clock that moved backwards must not read as stuck
			this.lastProgressTick = now;
			return;
		}
		long idle = now - this.lastProgressTick;
		if (idle <= this.jobGraceTicks) return;
		Constants.traceEvent("stuck {} job={} noProgressFor={}s", this.getUUID(), this.getAIState().jobId(), idle / 20L);
		this.reportProblem(FakeProblem.STUCK);
		FakeSpeech.say(this, Verbosity.NORMAL, "stuck", "I'm stuck");
		SenderAlerts.alert(level, this, "stuck", "has made no progress for " + (idle / 20L) + " seconds");
	}

	private static final double TRAVEL_COUNTS_SQ = 4.0D * 4.0D;

	/** Server side: show a line above this fake. Callers go through FakeSpeech, which gates and rate limits. */
	public void speak(String text) {
		if (this.level().isClientSide()) return;
		if (text.length() > SPEECH_MAX_CHARS) text = text.substring(0, SPEECH_MAX_CHARS);
		this.entityData.set(SPEECH, this.level().getGameTime() + "|" + text);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);

		builder.define(PHYSICAL_STATE, 0);
		builder.define(SKIN_DATA, new SkinData(PlayersConfig.get().defaultSkin).toNbt());
		builder.define(SLIM, false);
		builder.define(AI_STATE, new AIState().toNbt());
		builder.define(DISPLAY_ITEM, ItemStack.EMPTY);
		builder.define(SPEECH, "");
		builder.define(STATUS, (byte) 0);
		builder.define(CHARGING_CROSSBOW, false);
		builder.define(POSSESSOR, -1);
	}

	@Override
	public boolean canPickUpLoot() {
		return true;
	}

	// Let the fake pick up loot into empty slots (armor it lacks, an empty hand) but never swap out gear
	// it's already holding/wearing for whatever it walks over - MoveTowardsItemsGoal/JobHelpers.vacuum
	// handle deliberate collection into the inventory separately and don't go through this hook.
	@Override
	protected boolean canReplaceCurrentItem(ItemStack candidate, ItemStack existing, EquipmentSlot slot) {
		return existing.isEmpty();
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return !PlayersConfig.get().persistFakePlayers;
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean dropExperience) {
		super.dropCustomDeathLoot(level, source, dropExperience);

		Containers.dropContents(this.level(), this, this.inventory);
		if (chunkAnchored) {
			chunkAnchored = false;
			this.spawnAtLocation(level, FPItems.CHUNK_ANCHOR.get().getDefaultInstance());
		}

		ItemStack egg = FPItems.PLAYER_EGG.get().getDefaultInstance();
		egg.set(DataComponents.CUSTOM_NAME, this.getCustomName());
		this.spawnAtLocation(level, egg);
	}

	public PhysicalState getPhysicalState() {
		return PhysicalState.values()[this.entityData.get(PHYSICAL_STATE)];
	}

	public void setPhysicalState(PhysicalState state) {
		this.entityData.set(PHYSICAL_STATE, state.ordinal());
	}

	public boolean isSitting() {
		return this.getPhysicalState() == PhysicalState.SITTING;
	}

	public boolean isStanding() {
		return this.getPhysicalState() == PhysicalState.STANDING;
	}

	@Override
	public boolean hasPose(Pose pose) {
		if (pose == Pose.SLEEPING) {
			return this.getPhysicalState() == PhysicalState.LAYING;
		}

		return super.hasPose(pose);
	}

	@Override
	public boolean isNoAi() {
		// a possessed fake runs its controls even if its AI was switched off or it was lying down
		if (this.isPossessed()) return false;
		return super.isNoAi() || this.getPhysicalState() == PhysicalState.LAYING;
	}

	@Override
	public boolean isSleeping() {
		return super.isSleeping() || this.getPhysicalState() == PhysicalState.LAYING;
	}

	@Override
	public @Nullable Component getCustomName() {
		if (nameCache == null) {
			nameCache = Component.literal(this.getSkinData().name());
		}
		return nameCache;
	}

	@Override
	public void setCustomName(@Nullable Component component) {
		super.setCustomName(component);

		if (component == null) {
			this.setSkin(PlayersConfig.get().defaultSkin);
			return;
		}
		this.setSkin(component.getString());
	}

	public void setNameWithoutSkin(@Nullable Component component) {
		super.setCustomName(component);
		String name = component == null ? PlayersConfig.get().defaultSkin : component.getString();
		this.setSkin(this.getSkinData().withName(name));
		this.nameCache = null;
	}

	public ResourceLocation getSkin() {
		return this.getSkinData().getSkin();
	}

	public void setSkin(SkinData skin) {
		this.entityData.set(SKIN_DATA, skin.toNbt());
		this.dataCache = skin;
	}

	public void setSkin(String username) {
		this.setSkin(new SkinData(username));
	}

	public SkinData getSkinData() {
		if (dataCache == null) {
			dataCache = SkinData.fromNbt(this.entityData.get(SKIN_DATA));
		}

		return dataCache;
	}

	public boolean isSlim() {
		return this.entityData.get(SLIM);
	}

	public void setSlim(boolean val) {
		this.entityData.set(SLIM, val);
	}

	public AIState getAIState() {
		if (aiCache == null) {
			aiCache = AIState.fromNbt(this.entityData.get(AI_STATE));
		}
		return aiCache;
	}

	/** @return true; the CompoundTag-backed AI_STATE has no sync-size cap to fail against. */
	public boolean setAIState(AIState state) {
		this.entityData.set(AI_STATE, state.toNbt());
		this.aiCache = state;
		return true;
	}

	/** @return true; the CompoundTag-backed AI_STATE has no sync-size cap to fail against. */
	public boolean mutateAIState(java.util.function.Consumer<AIState> mutator) {
		AIState state = AIState.fromNbt(this.entityData.get(AI_STATE));
		mutator.accept(state);
		return setAIState(state);
	}

	public void sendChat(String message) {
		if (this.level().isClientSide) return;

		this.getServer().getPlayerList().broadcastChatMessage(PlayerChatMessage.system(message), this.createCommandSourceStackForNameResolution((net.minecraft.server.level.ServerLevel) this.level()), ChatType.bind(ChatType.CHAT, this));
	}

	/** Same {@code <name> message} line as {@link #sendChat}, but only {@code to} sees it. */
	public void sendChatTo(ServerPlayer to, String message) {
		if (this.level().isClientSide()) return;

		to.sendChatMessage(OutgoingChatMessage.create(PlayerChatMessage.system(message)), false, ChatType.bind(ChatType.CHAT, this));
	}


	public enum PhysicalState {
		STANDING,
		SITTING,
		LAYING
	}

	public record SkinData(String name, String key, String url) {
		public SkinData(String username) {
			this(username, username, SkinGrabber.SKIN_URL + username);
		}

		public static SkinData fromNbt(CompoundTag nbt) {
			String name = nbt.getString("Name");
			if (name.isEmpty()) {
				return new SkinData(PlayersConfig.get().defaultSkin);
			}
			String key = nbt.getString("Key");
			String url = nbt.getString("Url");
			if (key.isEmpty() || url.isEmpty()) {
				return new SkinData(name);
			}
			return new SkinData(name, key, url);
		}

		public CompoundTag toNbt() {
			CompoundTag nbt = new CompoundTag();
			nbt.putString("Name", this.name);
			nbt.putString("Key", this.key);
			nbt.putString("Url", this.url);
			return nbt;
		}

		public ResourceLocation getSkin() {
			return SkinGrabber.INSTANCE.getSkinOrDownload(this.key, this.url);
		}

		public SkinData withName(String name) {
			return new SkinData(name, this.key, this.url);
		}

		public SkinData withKey(String key) {
			return new SkinData(this.name, key, this.url);
		}

		public SkinData withUrl(String url) {
			return new SkinData(this.name, this.key, url);
		}
	}
}
