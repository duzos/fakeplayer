package dev.duzo.players.mixin;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
// remap=false: 26.x ships unobfuscated, so every target here is a mojmap name, same as FishingRodCastMixin
@Mixin(value = PersistentEntitySectionManager.class, remap = false)
public interface EntityManagerStorageAccessor {
	@Accessor(value = "permanentStorage", remap = false) EntityPersistentStorage<?> players$storage();
}
