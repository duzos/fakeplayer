package dev.duzo.players.entities;

import dev.duzo.players.api.InteractionRegistry;
import dev.duzo.players.api.SkinGrabber;
import dev.duzo.players.client.PlayersCommonClient;
import dev.duzo.players.core.FPEntities;
import dev.duzo.players.core.FPItems;
import dev.duzo.players.entities.goal.HumanoidWaterAvoidingRandomStrollGoal;
import dev.duzo.players.entities.goal.MoveTowardsItemsGoal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

public class FakePlayerEntity extends PathfinderMob {
	private static final EntityDataAccessor<Integer> PHYSICAL_STATE = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<CompoundTag> SKIN_DATA = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.COMPOUND_TAG);
	private static final EntityDataAccessor<Boolean> SLIM = SynchedEntityData.defineId(FakePlayerEntity.class, EntityDataSerializers.BOOLEAN);
	private SkinData dataCache;

	public FakePlayerEntity(EntityType<? extends FakePlayerEntity> type, Level level) {
		super(type, level);
	}

	public FakePlayerEntity(Level level) {
		this(FPEntities.FAKE_PLAYER.get(), level);
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (hand == InteractionHand.MAIN_HAND && player.level().isClientSide()) {
			if (player.isShiftKeyDown()) {
				PlayersCommonClient.openSelectScreen(this);

				return InteractionResult.SUCCESS;
			}
		}

		if (hand == InteractionHand.MAIN_HAND && !player.level().isClientSide()) {
			if (player.isShiftKeyDown()) {
				return InteractionResult.SUCCESS;
			}

			return InteractionRegistry.INSTANCE.get(player.getItemInHand(hand).getItem()).run((ServerPlayer) player, this);
		}

		return super.mobInteract(player, hand);
	}

	public static AttributeSupplier.Builder getHumanoidAttributes() {
		return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 25.0D).add(Attributes.MOVEMENT_SPEED, 0.2D).add(Attributes.ATTACK_DAMAGE, 1D);
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
	}

	@Override
	public void readAdditionalSaveData(CompoundTag nbt) {
		super.readAdditionalSaveData(nbt);

		this.dataCache = null;
		this.entityData.set(PHYSICAL_STATE, nbt.getInt("State"));
		this.entityData.set(SKIN_DATA, nbt.getCompound("SkinData"));
		this.entityData.set(SLIM, nbt.getBoolean("Slim"));
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
		super.onSyncedDataUpdated(data);

		if (SKIN_DATA.equals(data)) {
			this.dataCache = null;
		}
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();

		this.entityData.define(PHYSICAL_STATE, 0);
		this.entityData.define(SKIN_DATA, new SkinData("duzo").toNbt());
		this.entityData.define(SLIM, false);
	}

	@Override
	public boolean canPickUpLoot() {
		return true;
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override
	protected void dropCustomDeathLoot(DamageSource source, int p_21386_, boolean p_21387_) {
		super.dropCustomDeathLoot(source, p_21386_, p_21387_);

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
		return Component.literal(this.getSkinData().name());
	}

	@Override
	public void setCustomName(@Nullable Component component) {
		super.setCustomName(component);

		if (component == null) {
			this.setSkin("duzo");
			return;
		}
		this.setSkin(component.getString());
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
			return new SkinData(nbt.getString("Name"), nbt.getString("Key"), nbt.getString("Url"));
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
