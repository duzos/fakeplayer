package dev.duzo.players.mixin;

import cn.sh1rocu.tacz.api.mixin.ItemHandlerCapability;
import cn.sh1rocu.tacz.util.forge.LazyOptional;
import cn.sh1rocu.tacz.util.itemhandler.IItemHandler;
import cn.sh1rocu.tacz.util.itemhandler.InvWrapper;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

/**
 * Refabricated finds ammo through tacz$getItemHandler, which it injects into LivingEntity and answers with hands
 * and armour only. This hands it the fake's inventory instead. Only applied when TACZ is loaded, see
 * FabricMixinPlugin: the cn.sh1rocu types here do not exist otherwise.
 */
@Mixin(FakePlayerEntity.class)
public abstract class FakePlayerTaczAmmoMixin implements ItemHandlerCapability {
	@Unique
	private LazyOptional<IItemHandler> players$ammoHandler;

	@Override
	public LazyOptional<IItemHandler> tacz$getItemHandler(@Nullable Direction facing) {
		if (facing != null) return LazyOptional.empty();
		if (this.players$ammoHandler == null) {
			FakePlayerEntity fake = (FakePlayerEntity) (Object) this;
			this.players$ammoHandler = LazyOptional.of(() -> new InvWrapper(fake.getInventory()));
		}
		return this.players$ammoHandler;
	}
}
