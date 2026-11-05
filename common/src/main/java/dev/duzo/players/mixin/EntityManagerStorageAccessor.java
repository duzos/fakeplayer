package dev.duzo.players.mixin;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(PersistentEntitySectionManager.class)
public interface EntityManagerStorageAccessor {
	@Accessor("permanentStorage") EntityPersistentStorage<?> players$storage();
}
