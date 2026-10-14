package dev.duzo.players.mixin;

import cn.sh1rocu.tacz.api.mixin.ItemHandlerCapability;
import cn.sh1rocu.tacz.util.forge.LazyOptional;
import cn.sh1rocu.tacz.util.itemhandler.IItemHandler;
import cn.sh1rocu.tacz.util.itemhandler.CombinedInvWrapper;
import cn.sh1rocu.tacz.util.itemhandler.InvWrapper;
import cn.sh1rocu.tacz.util.itemhandler.entity.EntityHandsInvWrapper;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

/**
 * Refabricated finds ammo through tacz$getItemHandler, which it injects into LivingEntity and answers with hands
 * and armour only. This hands it the fake's inventory plus hands, and nothing once the fake is dead. Only applied when TACZ is loaded, see
 * FabricMixinPlugin: the cn.sh1rocu types here do not exist otherwise.
 */
@Mixin(FakePlayerEntity.class)
public abstract class FakePlayerTaczAmmoMixin implements ItemHandlerCapability {
	@Unique
	private LazyOptional<IItemHandler> players$ammoHandler;

	@Override
	public LazyOptional<IItemHandler> tacz$getItemHandler(@Nullable Direction facing) {
		FakePlayerEntity fake = (FakePlayerEntity) (Object) this;
		if (facing != null || !fake.isAlive()) return LazyOptional.empty();
		if (this.players$ammoHandler == null) {
			this.players$ammoHandler = LazyOptional.of(() -> new CombinedInvWrapper(
					new InvWrapper(fake.getInventory()), new EntityHandsInvWrapper(fake)));
		}
		return this.players$ammoHandler;
	}
}
