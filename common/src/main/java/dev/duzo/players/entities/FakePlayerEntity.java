package dev.duzo.players.entities;

import dev.duzo.players.api.InteractionRegistry;
import dev.duzo.players.api.SkinGrabber;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPItems;
import dev.duzo.players.entities.ai.AIState;
import dev.duzo.players.entities.ai.Job;
import dev.duzo.players.entities.ai.JobExecutor;
import dev.duzo.players.entities.ai.JobExecutors;
import net.minecraft.nbt.CompoundTag;
import dev.duzo.players.entities.goal.FollowOwnerGoal;
import dev.duzo.players.entities.goal.HumanoidWaterAvoidingRandomStrollGoal;
import dev.duzo.players.entities.goal.MoveTowardsItemsGoal;
import dev.duzo.players.entities.inventory.FakePlayerInventory;
import dev.duzo.players.menu.FakePlayerMenuProvider;
import dev.duzo.players.platform.Services;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

public class FakePlayerEntity extends PathfinderMob {
	private static final EntityDataAccessor<Integer> PHYSICAL_STATE = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> SKIN_NAME = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> SKIN_KEY = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> SKIN_URL = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Boolean> SLIM = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<String> AI_STATE = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.STRING);
	private SkinData dataCache;
	private AIState aiCache;
	private Component nameCache;
	private final FakePlayerInventory inventory = new FakePlayerInventory(this);
	private JobExecutor jobExecutor;
	private Job jobExecutorJob = Job.NONE;
	private boolean jobPaused;
	private boolean jobPausedPrev;

	public FakePlayerEntity(EntityType<? extends FakePlayerEntity> type, Level level) {
		super(type, level);
	}

	public FakePlayerEntity(Level level) {
		this(FPEntities.FAKE_PLAYER.get(), level);
	}

	@Override
	public void tick() {
		super.tick();
		this.updateSwingTime();
		if (this.level() instanceof ServerLevel server) {
			this.tickJobExecutor(server);
		}
	}

	private void tickJobExecutor(ServerLevel level) {
		AIState state = this.getAIState();
		Job job = state.job();
		if (jobExecutor == null || job != jobExecutorJob) {
			jobExecutor = JobExecutors.create(job);
			jobExecutor.deserialize(state.jobState());
			jobExecutorJob = job;
			jobPausedPrev = false;
		}
		if (jobPaused != jobPausedPrev) {
			if (jobPaused) jobExecutor.onPause(this);
			else jobExecutor.onResume(this);
			jobPausedPrev = jobPaused;
		}
		if (state.running() && !jobPaused) {
			jobExecutor.tick(level, this);
		}
	}

	public void setJobPaused(boolean paused) {
		this.jobPaused = paused;
	}

	public boolean isJobPaused() {
		return this.jobPaused;
	}

	public boolean isMovementManagedByJob() {
		AIState state = this.getAIState();
		if (!state.running() || jobPaused) return false;
		Job job = state.job();
		return job == Job.IDLE || job == Job.GUARD;
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return super.mobInteract(player, hand);
		}

		if (player.isShiftKeyDown()) {
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

	public static AttributeSupplier.Builder getHumanoidAttributes() {
		PlayersConfig config = PlayersConfig.get();
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, config.maxHealth)
				.add(Attributes.MOVEMENT_SPEED, config.movementSpeed)
				.add(Attributes.ATTACK_DAMAGE, config.attackDamage)
				.add(Attributes.TEMPT_RANGE);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(7, new HumanoidWaterAvoidingRandomStrollGoal(this, 1.0D));
		this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
		this.goalSelector.addGoal(5, new OpenDoorGoal(this, true));
		this.goalSelector.addGoal(4, new HurtByTargetGoal(this));
		this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.5D, true));
		this.goalSelector.addGoal(2, new MoveTowardsItemsGoal(this, 1.0D, true));
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
	public void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);

		output.putInt("State", this.getPhysicalState().ordinal());
		SkinData skin = this.getSkinData();
		ValueOutput skinOut = output.child("SkinData");
		skinOut.putString("Name", skin.name());
		skinOut.putString("Key", skin.key());
		skinOut.putString("Url", skin.url());
		output.putBoolean("Slim", this.isSlim());
		this.flushJobState();
		output.putString("AIState", this.entityData.get(AI_STATE));
		this.inventory.storeAsItemList(output.list("Inventory", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC));
	}

	private void flushJobState() {
		if (jobExecutor == null) return;
		CompoundTag tag = jobExecutor.serialize();
		final CompoundTag finalTag = tag == null ? new CompoundTag() : tag;
		this.mutateAIState(s -> s.setJobState(finalTag));
	}

	@Override
	public void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);

		this.dataCache = null;
		this.aiCache = null;
		this.nameCache = null;
		this.entityData.set(PHYSICAL_STATE, input.getIntOr("State", 0));
		ValueInput skinIn = input.childOrEmpty("SkinData");
		String name = skinIn.getStringOr("Name", "");
		SkinData skin;
		if (name.isEmpty()) {
			skin = new SkinData(PlayersConfig.get().defaultSkin);
		} else {
			String key = skinIn.getStringOr("Key", "");
			String url = skinIn.getStringOr("Url", "");
			skin = (key.isEmpty() || url.isEmpty()) ? new SkinData(name) : new SkinData(name, key, url);
		}
		this.applySkin(skin);
		this.entityData.set(SLIM, input.getBooleanOr("Slim", false));
		String aiSnbt = input.getStringOr("AIState", "");
		if (!aiSnbt.isEmpty()) this.entityData.set(AI_STATE, aiSnbt);
		input.list("Inventory", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC).ifPresent(this.inventory::fromItemList);
	}

	public FakePlayerInventory getInventory() {
		return inventory;
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
		super.onSyncedDataUpdated(data);

		if (SKIN_NAME.equals(data) || SKIN_KEY.equals(data) || SKIN_URL.equals(data)) {
			this.dataCache = null;
			this.nameCache = null;
		} else if (AI_STATE.equals(data)) {
			this.aiCache = null;
		}
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);

		SkinData defaultSkin = new SkinData(PlayersConfig.get().defaultSkin);
		builder.define(PHYSICAL_STATE, 0);
		builder.define(SKIN_NAME, defaultSkin.name());
		builder.define(SKIN_KEY, defaultSkin.key());
		builder.define(SKIN_URL, defaultSkin.url());
		builder.define(SLIM, false);
		builder.define(AI_STATE, new AIState().toNbt().toString());
	}

	private void applySkin(SkinData skin) {
		this.entityData.set(SKIN_NAME, skin.name());
		this.entityData.set(SKIN_KEY, skin.key());
		this.entityData.set(SKIN_URL, skin.url());
		this.dataCache = skin;
	}

	@Override
	public boolean canPickUpLoot() {
		return true;
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return !PlayersConfig.get().persistFakePlayers;
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean dropExperience) {
		super.dropCustomDeathLoot(level, source, dropExperience);

		Containers.dropContents(this.level(), this, this.inventory);

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

	public Identifier getSkin() {
		return this.getSkinData().getSkin();
	}

	public void setSkin(SkinData skin) {
		this.applySkin(skin);
	}

	public void setSkin(String username) {
		this.setSkin(new SkinData(username));
	}

	public SkinData getSkinData() {
		if (dataCache == null) {
			dataCache = new SkinData(
				this.entityData.get(SKIN_NAME),
				this.entityData.get(SKIN_KEY),
				this.entityData.get(SKIN_URL)
			);
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
			aiCache = AIState.fromNbt(parseAiSnbt(this.entityData.get(AI_STATE)));
		}
		return aiCache;
	}

	public void setAIState(AIState state) {
		this.entityData.set(AI_STATE, state.toNbt().toString());
		this.aiCache = state;
	}

	public void mutateAIState(java.util.function.Consumer<AIState> mutator) {
		AIState state = AIState.fromNbt(parseAiSnbt(this.entityData.get(AI_STATE)));
		mutator.accept(state);
		setAIState(state);
	}

	private static CompoundTag parseAiSnbt(String snbt) {
		if (snbt == null || snbt.isEmpty()) return new CompoundTag();
		try {
			return net.minecraft.nbt.TagParser.parseCompoundFully(snbt);
		} catch (Exception e) {
			return new CompoundTag();
		}
	}

	public void sendChat(String message) {
		if (this.level().isClientSide()) return;

		this.level().getServer().getPlayerList().broadcastChatMessage(PlayerChatMessage.system(message), this.createCommandSourceStackForNameResolution((net.minecraft.server.level.ServerLevel) this.level()), ChatType.bind(ChatType.CHAT, this));
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

		public Identifier getSkin() {
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
