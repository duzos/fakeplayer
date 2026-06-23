package dev.duzo.players.core;

import dev.duzo.players.Constants;
import dev.duzo.players.entities.FakeFishingHook;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.platform.Services;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.function.Supplier;

public class FPEntities {
	public static final Supplier<EntityType<FakePlayerEntity>> FAKE_PLAYER = register("fake_player", () -> EntityType.Builder.<FakePlayerEntity>of(FakePlayerEntity::new, MobCategory.MISC).sized(0.6F, 1.8F).build("fake_player"));
	public static final Supplier<EntityType<FakeFishingHook>> FISHING_HOOK = register("fishing_hook", () -> EntityType.Builder.<FakeFishingHook>of(FakeFishingHook::new, MobCategory.MISC).sized(0.25F, 0.25F).clientTrackingRange(5).updateInterval(1).build("fishing_hook"));

	public static <T extends Entity> Supplier<EntityType<T>> register(String name, Supplier<EntityType<T>> item) {
		return Services.COMMON_REGISTRY.registerEntity(Constants.MOD_ID, name, item);
	}

	public static void init() {
		Services.COMMON_REGISTRY.registerAttributes(FAKE_PLAYER, FakePlayerEntity::getHumanoidAttributes);
	}
}
