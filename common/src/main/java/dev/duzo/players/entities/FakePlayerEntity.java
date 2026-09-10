package dev.duzo.players.entities;

import dev.duzo.players.Constants;
import dev.duzo.players.api.CustomBindTracker;
import dev.duzo.players.api.InteractionRegistry;
import dev.duzo.players.api.SkinGrabber;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPItems;
import dev.duzo.players.entities.ai.AIState;
import dev.duzo.players.entities.ai.Job;
import dev.duzo.players.entities.ai.JobExecutor;
import dev.duzo.players.entities.ai.JobExecutors;
import dev.duzo.players.entities.ai.RangedWeapon;
import dev.duzo.players.entities.goal.FakeRangedAttackGoal;
import dev.duzo.players.entities.goal.FollowOwnerGoal;
import dev.duzo.players.entities.goal.HumanoidWaterAvoidingRandomStrollGoal;
import dev.duzo.players.entities.goal.MoveTowardsItemsGoal;
import dev.duzo.players.entities.inventory.FakePlayerInventory;
import dev.duzo.players.menu.FakeCrafterMenu;
import dev.duzo.players.menu.FakePlayerMenu;
import dev.duzo.players.menu.FakePlayerMenuProvider;
import dev.duzo.players.platform.Services;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import dev.duzo.players.entities.ai.FakeHurtByTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import net.minecraft.sounds.SoundEvents;

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
	// drives the client-side crossbow charging pose; combat state only, never persisted
	private static final EntityDataAccessor<Boolean> CHARGING_CROSSBOW = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.BOOLEAN);
	private SkinData dataCache;
	private AIState aiCache;
	private Component nameCache;
	private final FakePlayerInventory inventory = new FakePlayerInventory(this);
	private JobExecutor jobExecutor;
	private Job jobExecutorJob = Job.NONE;
	private boolean jobPaused;
	private boolean jobActivePrev;

	public FakePlayerEntity(EntityType<? extends FakePlayerEntity> type, Level level) {
		super(type, level);
		// vanilla's DropChances.DEFAULT only drops equipment ~8.5% of the time; the fake's gear should
		// never be destroyed by death or lava just because it wasn't recently hit by a player.
		for (EquipmentSlot slot : EquipmentSlot.values()) this.setGuaranteedDrop(slot);
	}

	public FakePlayerEntity(Level level) {
		this(FPEntities.FAKE_PLAYER.get(), level);
	}

	@Override
	public void tick() {
		super.tick();
		this.updateSwingTime();
		if (this.level() instanceof ServerLevel server) {
			this.updateFollowOverridePause();
			this.tickJobExecutor(server);
		}
	}

	// Pause the running job while the owner is holding a bound marker or has this fake's menu open,
	// so the follow-override goal can take over movement without losing the job.
	private void updateFollowOverridePause() {
		setJobPaused(getFollowOverrideOwner() != null);
	}

	private void tickJobExecutor(ServerLevel level) {
		AIState state = this.getAIState();
		Job job = state.job();
		if (jobExecutor == null || job != jobExecutorJob) {
			jobExecutor = JobExecutors.create(job);
			jobExecutor.deserialize(state.jobState());
			jobExecutorJob = job;
			jobActivePrev = false;
		}
		boolean active = state.running() && !jobPaused;
		if (active != jobActivePrev) {
			if (active) jobExecutor.onResume(this);
			else jobExecutor.onPause(this);
			jobActivePrev = active;
		}
		if (active) {
			jobExecutor.tick(level, this);
		}
	}

	public void setJobPaused(boolean paused) {
		this.jobPaused = paused;
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
		boolean jobFollow = state.running() && state.job() == Job.FOLLOW;
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
		return state.job() != Job.NONE;
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return super.mobInteract(player, hand);
		}

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
		return !this.isMovementManagedByJob() || this.getAIState().job() == Job.GUARD;
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

		// no weapon ItemStack parameter on this version: enchantments on the bow are not applied to the arrow
		AbstractArrow arrow = ProjectileUtil.getMobArrow(this, ammo.copyWithCount(1), velocity);
		// the fake paid for this arrow out of its inventory, so let it be picked back up
		arrow.pickup = AbstractArrow.Pickup.ALLOWED;
		this.shootAt(server, target, arrow, ammo);
		ammo.shrink(1);
		this.inventory.setChanged();
		weapon.hurtAndBreak(1, this, e -> e.broadcastBreakEvent(hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND));
		this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
	}

	// A thrown trident is not recoverable, so the held one is only damaged rather than consumed. Making the
	// copy pickup-able instead would hand the thrower a second trident every throw.
	private void throwTrident(ServerLevel server, LivingEntity target, ItemStack weapon, InteractionHand hand) {
		ThrownTrident trident = new ThrownTrident(server, this, weapon.copyWithCount(1));
		trident.pickup = AbstractArrow.Pickup.DISALLOWED;
		this.shootAt(server, target, trident, weapon);
		weapon.hurtAndBreak(1, this, e -> e.broadcastBreakEvent(hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND));
		this.playSound(SoundEvents.DROWNED_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
	}

	// Same lob and same difficulty-scaled inaccuracy vanilla's skeleton uses. Projectile.spawnProjectileUsingShoot
	// does not exist on this version, so the projectile is shot and added to the level directly, the way vanilla
	// itself did before that helper was introduced.
	private void shootAt(ServerLevel server, LivingEntity target, AbstractArrow projectile, ItemStack from) {
		double dx = target.getX() - this.getX();
		double dy = target.getY(0.3333333333333333D) - projectile.getY();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		projectile.shoot(dx, dy + horizontal * 0.20000000298023224D, dz,
				1.6F, 14 - server.getLevelData().getDifficulty().getId() * 4);
		server.addFreshEntity(projectile);
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

	// CrossbowAttackMob.shootCrossbowProjectile(LivingEntity, ItemStack, Projectile, float) has no default body
	// on this version (only the 5-arg shooter/target overload does), unlike 1.21.11 where the reference needed
	// no override at all. Vanilla's own CrossbowAttackMob implementations (e.g. Pillager) all forward like this.
	@Override
	public void shootCrossbowProjectile(LivingEntity shooter, ItemStack weapon, Projectile projectile, float velocity) {
		this.shootCrossbowProjectile(this, shooter, projectile, velocity, 1.6F);
	}

	public static AttributeSupplier.Builder getHumanoidAttributes() {
		PlayersConfig config = PlayersConfig.get();
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, config.maxHealth)
				.add(Attributes.MOVEMENT_SPEED, config.movementSpeed)
				.add(Attributes.ATTACK_DAMAGE, config.attackDamage)
				.add(Attributes.FOLLOW_RANGE, Math.max(16.0, Math.min(2048.0, config.pathRange)));
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(7, new HumanoidWaterAvoidingRandomStrollGoal(this, 1.0D));
		this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
		this.goalSelector.addGoal(5, new OpenDoorGoal(this, true));
		this.targetSelector.addGoal(1, new FakeHurtByTargetGoal(this));
		this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.5D, true));
		this.goalSelector.addGoal(2, new MoveTowardsItemsGoal(this, 1.0D, true));
		// above the item-pickup goal on purpose: goals only yield the movement flag to a strictly better
		// priority, so at 2 a fake already walking to a drop could never break off to shoot
		this.goalSelector.addGoal(1, new FakeRangedAttackGoal(this, 1.0D, 15.0F));
		this.goalSelector.addGoal(1, new TemptGoal(this, 1.0D, Ingredient.of(Items.REDSTONE_BLOCK, Items.REDSTONE_TORCH), false));
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
		nbt.put("Inventory", this.inventory.createTag());
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

	@Override
	public void remove(RemovalReason reason) {
		// also fires on chunk unload and dimension change, which is what keeps the cache bounded;
		// a rebuilt index costs one rescan. On CHANGED_DIMENSION level() is still the old level,
		// so the key forgotten is the right one.
		if (this.level() instanceof ServerLevel level) {
			dev.duzo.players.entities.ai.requests.PoolIndex.forget(level, this.getUUID());
		}
		super.remove(reason);
	}

	public void flushJobState() {
		if (jobExecutor == null) return;
		CompoundTag tag = jobExecutor.serialize();
		final CompoundTag finalTag = tag == null ? new CompoundTag() : tag;
		this.mutateAIState(s -> s.setJobState(finalTag));
	}

	public void resetJobExecutor() {
		// run the pause path before discarding the executor - otherwise stopping the job never
		// fires onPause. Flush after onPause runs so the persisted state reflects what onPause
		// just changed, not the stale mid-job state that existed before it ran.
		if (this.jobExecutor != null) {
			this.jobExecutor.onPause(this);
			this.flushJobState();
		}
		this.jobExecutor = null;
		this.jobExecutorJob = Job.NONE;
		this.jobActivePrev = false;
	}
	@Override
	public void readAdditionalSaveData(CompoundTag nbt) {
		super.readAdditionalSaveData(nbt);

		// super's read unconditionally replaces dropChances with the default (~8.5%) when the save
		// predates guaranteed drops, and addAdditionalSaveData only writes the key when non-default,
		// so it never self-heals; re-apply the guarantee every load instead.
		for (EquipmentSlot slot : EquipmentSlot.values()) this.setGuaranteedDrop(slot);

		this.dataCache = null;
		this.aiCache = null;
		this.nameCache = null;
		this.entityData.set(PHYSICAL_STATE, nbt.getInt("State"));
		this.entityData.set(SKIN_DATA, nbt.getCompound("SkinData"));
		this.entityData.set(SLIM, nbt.getBoolean("Slim"));
		if (nbt.contains("AIState")) this.entityData.set(AI_STATE, nbt.getCompound("AIState"));
		if (nbt.contains("Inventory", Tag.TAG_LIST)) {
			this.inventory.fromTag(nbt.getList("Inventory", Tag.TAG_COMPOUND));
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
		}
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();

		this.entityData.define(PHYSICAL_STATE, 0);
		this.entityData.define(SKIN_DATA, new SkinData(PlayersConfig.get().defaultSkin).toNbt());
		this.entityData.define(SLIM, false);
		this.entityData.define(AI_STATE, new AIState().toNbt());
		this.entityData.define(DISPLAY_ITEM, ItemStack.EMPTY);
		this.entityData.define(CHARGING_CROSSBOW, false);
	}

	@Override
	public boolean canPickUpLoot() {
		return true;
	}

	// Let the fake pick up loot into empty slots (armor it lacks, an empty hand) but never swap out gear
	// it's already holding/wearing for whatever it walks over - MoveTowardsItemsGoal/JobHelpers.vacuum
	// handle deliberate collection into the inventory separately and don't go through this hook.
	@Override
	protected boolean canReplaceCurrentItem(ItemStack candidate, ItemStack existing) {
		return existing.isEmpty();
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return !PlayersConfig.get().persistFakePlayers;
	}

	@Override
	protected void dropCustomDeathLoot(DamageSource source, int p_21386_, boolean p_21387_) {
		super.dropCustomDeathLoot(source, p_21386_, p_21387_);

		Containers.dropContents(this.level(), this, this.inventory);

		ItemStack egg = FPItems.PLAYER_EGG.get().getDefaultInstance();
		egg.setHoverName(this.getCustomName());
		this.spawnAtLocation(egg);
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

	public void setAIState(AIState state) {
		this.entityData.set(AI_STATE, state.toNbt());
		this.aiCache = state;
	}

	public void mutateAIState(java.util.function.Consumer<AIState> mutator) {
		AIState state = AIState.fromNbt(this.entityData.get(AI_STATE));
		mutator.accept(state);
		setAIState(state);
	}

	public void sendChat(String message) {
		if (this.level().isClientSide) return;

		this.getServer().getPlayerList().broadcastChatMessage(PlayerChatMessage.system(message), this.createCommandSourceStack(), ChatType.bind(ChatType.CHAT, this));
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
