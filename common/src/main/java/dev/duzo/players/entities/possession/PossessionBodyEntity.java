package dev.duzo.players.entities.possession;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import javax.annotation.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a possessing player leaves behind: a standing copy of them, dressed as they are but made of nothing. It carries no
 * items, since the real stacks are in the stash, and it cannot lose health. Any hit on it ends the possession and goes to
 * the player instead.
 */
public class PossessionBodyEntity extends LivingEntity {
	private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(PossessionBodyEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> OWNER_NAME = SynchedEntityData.defineId(PossessionBodyEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<ItemStack> SHOWN_MAINHAND = SynchedEntityData.defineId(PossessionBodyEntity.class, EntityDataSerializers.ITEM_STACK);
	private static final EntityDataAccessor<ItemStack> SHOWN_OFFHAND = SynchedEntityData.defineId(PossessionBodyEntity.class, EntityDataSerializers.ITEM_STACK);
	private static final EntityDataAccessor<ItemStack> SHOWN_HEAD = SynchedEntityData.defineId(PossessionBodyEntity.class, EntityDataSerializers.ITEM_STACK);
	private static final EntityDataAccessor<ItemStack> SHOWN_CHEST = SynchedEntityData.defineId(PossessionBodyEntity.class, EntityDataSerializers.ITEM_STACK);
	private static final EntityDataAccessor<ItemStack> SHOWN_LEGS = SynchedEntityData.defineId(PossessionBodyEntity.class, EntityDataSerializers.ITEM_STACK);
	private static final EntityDataAccessor<ItemStack> SHOWN_FEET = SynchedEntityData.defineId(PossessionBodyEntity.class, EntityDataSerializers.ITEM_STACK);

	/** The worn and held items drawn on the body. Display copies only: they are never saved, never dropped and never given back. */
	private static final Map<EquipmentSlot, EntityDataAccessor<ItemStack>> SHOWN = new EnumMap<>(EquipmentSlot.class);

	static {
		SHOWN.put(EquipmentSlot.MAINHAND, SHOWN_MAINHAND);
		SHOWN.put(EquipmentSlot.OFFHAND, SHOWN_OFFHAND);
		SHOWN.put(EquipmentSlot.HEAD, SHOWN_HEAD);
		SHOWN.put(EquipmentSlot.CHEST, SHOWN_CHEST);
		SHOWN.put(EquipmentSlot.LEGS, SHOWN_LEGS);
		SHOWN.put(EquipmentSlot.FEET, SHOWN_FEET);
	}

	private static final int SESSION_CHECK_TICKS = 20;

	public PossessionBodyEntity(EntityType<? extends PossessionBodyEntity> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return LivingEntity.createLivingAttributes();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(OWNER, "");
		builder.define(OWNER_NAME, "");
		for (EntityDataAccessor<ItemStack> accessor : SHOWN.values()) builder.define(accessor, ItemStack.EMPTY);
	}

	/** Server side. Whose body this is, shown above it and used by the client to find their skin. */
	public void setOwner(UUID uuid, String name) {
		this.entityData.set(OWNER, uuid.toString());
		this.entityData.set(OWNER_NAME, name);
		this.setCustomName(Component.literal(name));
		this.setCustomNameVisible(true);
	}

	@Nullable
	public UUID getOwner() {
		try {
			String raw = this.entityData.get(OWNER);
			return raw.isEmpty() ? null : UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public String getOwnerName() {
		return this.entityData.get(OWNER_NAME);
	}

	/** Server side. Sets what is drawn in a slot to a copy of the stack; the stack passed in is not kept. */
	public void showItem(EquipmentSlot slot, ItemStack shown) {
		EntityDataAccessor<ItemStack> accessor = SHOWN.get(slot);
		if (accessor == null || ItemStack.matches(this.entityData.get(accessor), shown)) return;
		this.entityData.set(accessor, shown.isEmpty() ? ItemStack.EMPTY : shown.copy());
	}

	/** Only the client has anything to show; the server-side body carries nothing. */
	@Override
	public ItemStack getItemBySlot(EquipmentSlot slot) {
		EntityDataAccessor<ItemStack> accessor = SHOWN.get(slot);
		if (accessor == null || !this.level().isClientSide()) return ItemStack.EMPTY;
		return this.entityData.get(accessor);
	}

	@Override
	public void setItemSlot(EquipmentSlot slot, ItemStack stack) {}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean canBeAffected(MobEffectInstance effect) {
		return false;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		PossessionManager.onBodyHurt(this, level, source, amount);
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level() instanceof ServerLevel level && this.tickCount % SESSION_CHECK_TICKS == 0 && !this.isRemoved()) {
			PossessionManager.checkBody(this, level);
		}
	}

	@Override
	public void addAdditionalSaveData(CompoundTag output) {
		super.addAdditionalSaveData(output);
		output.putString("Owner", this.entityData.get(OWNER));
		output.putString("OwnerName", this.entityData.get(OWNER_NAME));
	}

	@Override
	public void readAdditionalSaveData(CompoundTag input) {
		super.readAdditionalSaveData(input);
		this.entityData.set(OWNER, input.getStringOr("Owner", ""));
		this.entityData.set(OWNER_NAME, input.getStringOr("OwnerName", ""));
	}
}
