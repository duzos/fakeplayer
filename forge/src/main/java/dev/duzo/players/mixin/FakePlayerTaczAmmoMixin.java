package dev.duzo.players.mixin;

import dev.duzo.players.compat.TaczGunBridge;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.wrapper.EntityHandsInvWrapper;
import net.minecraftforge.items.wrapper.InvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * TACZ finds ammo with getCapability(ITEM_HANDLER, null). LivingEntity answers that itself while alive, with hands
 * and armour only, and never reaches attached capabilities, so this hands out the fake's inventory plus hands instead.
 * Only does so when TACZ is loaded, the capability is visible to every mod that asks for it.
 */
@Mixin(FakePlayerEntity.class)
public abstract class FakePlayerTaczAmmoMixin extends PathfinderMob {
	@Unique
	private LazyOptional<IItemHandler> players$ammoHandler;

	protected FakePlayerTaczAmmoMixin(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
	}

	@Override
	@Nonnull
	public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
		if (cap == ForgeCapabilities.ITEM_HANDLER && side == null && this.isAlive() && TaczGunBridge.present()) {
			if (this.players$ammoHandler == null || !this.players$ammoHandler.isPresent()) {
				FakePlayerEntity fake = (FakePlayerEntity) (Object) this;
				this.players$ammoHandler = LazyOptional.of(() -> new CombinedInvWrapper(
						new InvWrapper(fake.getInventory()), new EntityHandsInvWrapper(fake)));
			}
			return this.players$ammoHandler.cast();
		}
		return super.getCapability(cap, side);
	}
}
