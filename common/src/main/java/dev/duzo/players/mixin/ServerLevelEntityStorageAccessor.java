package dev.duzo.players.mixin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
// remap=false: 26.x ships unobfuscated, so every target here is a mojmap name, same as FishingRodCastMixin
@Mixin(value = ServerLevel.class, remap = false)
public interface ServerLevelEntityStorageAccessor {
	@Accessor(value = "entityManager", remap = false) PersistentEntitySectionManager<Entity> players$storage();
}
