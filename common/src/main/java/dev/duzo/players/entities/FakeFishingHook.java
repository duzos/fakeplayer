package dev.duzo.players.entities;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.Projectile;
import dev.duzo.players.entities.ai.AIState;
import dev.duzo.players.entities.ai.Job;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown fishing bobber owned by a {@link FakePlayerEntity}. The fake is a PathfinderMob, not a
 * Player, so this is a standalone projectile rather than a vanilla FishingHook. Physics only: it
 * arcs out, settles on the water surface and bobs. The Fisherman executor drives cast/bite/reel
 * timing and removes it; visuals (line + bobber) are drawn by FishingLineRenderer.
 */
public class FakeFishingHook extends Projectile {
	private static final EntityDataAccessor<Integer> DATA_OWNER_ID = SynchedEntityData.defineId(FakeFishingHook.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> DATA_BITING = SynchedEntityData.defineId(FakeFishingHook.class, EntityDataSerializers.BOOLEAN);
	private static final int MAX_LIFE = 20 * 90;

	private enum State { FLYING, BOBBING }

	private State state = State.FLYING;
	private int life = 0;
	private Vec3 target;           // server-only: water surface the executor aimed at
	private double targetSurfaceY;

	public FakeFishingHook(EntityType<? extends FakeFishingHook> type, Level level) {
		super(type, level);
	}

	public FakeFishingHook(Level level, FakePlayerEntity owner) {
		this(dev.duzo.players.core.FPEntities.FISHING_HOOK.get(), level);
		this.setOwner(owner);
		this.entityData.set(DATA_OWNER_ID, owner.getId());
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_OWNER_ID, 0);
		builder.define(DATA_BITING, false);
	}

	/** Server-side: the water surface the bobber should settle on. */
	public void aimAt(Vec3 target, double surfaceY) {
		this.target = target;
		this.targetSurfaceY = surfaceY;
	}

	public int ownerId() {
		return this.entityData.get(DATA_OWNER_ID);
	}

	public boolean isBiting() {
		return this.entityData.get(DATA_BITING);
	}

	public void setBiting(boolean biting) {
		this.entityData.set(DATA_BITING, biting);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide()) return;

		if (++life > MAX_LIFE) { discard(); return; }
		// auto-kill: no resolvable owner, or owner not actively running the fisherman job
		if (!(this.getOwner() instanceof FakePlayerEntity fake) || !fake.isAlive()) { discard(); return; }
		AIState ai = fake.getAIState();
		if (ai.job() != Job.FISHERMAN || !ai.running()) { discard(); return; }
		// re-sync the owner id for the client (synced data isn't persisted; entity ids change on reload)
		if (this.entityData.get(DATA_OWNER_ID) != fake.getId()) this.entityData.set(DATA_OWNER_ID, fake.getId());
		// singleton: match on the owner ENTITY (the persisted UUID), not the synced id; keep the newest
		if (!this.level().getEntitiesOfClass(FakeFishingHook.class, this.getBoundingBox().inflate(64.0),
				h -> h != this && h.getOwner() == fake && h.getId() > this.getId()).isEmpty()) {
			discard();
			return;
		}

		Vec3 dm = this.getDeltaMovement();
		FluidState fluid = this.level().getFluidState(this.blockPosition());
		float fluidHeight = fluid.is(FluidTags.WATER) ? fluid.getHeight(this.level(), this.blockPosition()) : 0F;
		boolean inWater = fluidHeight > 0;

		switch (state) {
			case FLYING -> {
				if (target != null) {
					double ddx = target.x - this.getX(), ddz = target.z - this.getZ();
					if (ddx * ddx + ddz * ddz < 0.64) { // reached the water column: snap onto the surface
						this.setPos(target.x, targetSurfaceY, target.z);
						this.setDeltaMovement(Vec3.ZERO);
						state = State.BOBBING;
						return;
					}
				}
				if (inWater) {
					this.setDeltaMovement(dm.multiply(0.3, 0.2, 0.3));
					state = State.BOBBING;
				} else if (this.horizontalCollision || this.onGround()) {
					this.setDeltaMovement(Vec3.ZERO);
				} else {
					this.setDeltaMovement(dm.scale(0.99).subtract(0, 0.03, 0));
					this.move(MoverType.SELF, this.getDeltaMovement());
				}
			}
			case BOBBING -> {
				if (!inWater) {
					this.setDeltaMovement(dm.subtract(0, 0.03, 0));
					this.move(MoverType.SELF, this.getDeltaMovement());
					state = State.FLYING;
					break;
				}
				double targetY = this.blockPosition().getY() + fluidHeight;
				double dy = targetY - this.getY();
				this.setDeltaMovement(dm.x * 0.9, dm.y + dy * 0.1, dm.z * 0.9);
				this.move(MoverType.SELF, this.getDeltaMovement());
				this.setDeltaMovement(this.getDeltaMovement().scale(0.9));
			}
		}
	}

	@Override
	protected double getDefaultGravity() {
		return 0.0; // gravity handled manually in tick
	}

	@Override
	protected boolean canHitEntity(Entity entity) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("HookState", state.name());
		output.putInt("Life", life);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		try { state = State.valueOf(input.getStringOr("HookState", "FLYING")); } catch (IllegalArgumentException ignored) {}
		life = input.getIntOr("Life", 0);
	}
}
