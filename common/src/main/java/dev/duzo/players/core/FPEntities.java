package dev.duzo.players.core;

import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.platform.Services;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.function.Supplier;

public class FPEntities {
	public static final Supplier<EntityType<FakePlayerEntity>> FAKE_PLAYER = register("fake_player", () -> EntityType.Builder.<FakePlayerEntity>of(FakePlayerEntity::new, MobCategory.MISC).sized(0.6F, 1.8F).build(ResourceKey.create(Registries.ENTITY_TYPE, PlayersCommon.id("fake_player"))));

	public static <T extends Entity> Supplier<EntityType<T>> register(String name, Supplier<EntityType<T>> item) {
		return Services.COMMON_REGISTRY.registerEntity(Constants.MOD_ID, name, item);
	}

	public static void init() {
		Services.COMMON_REGISTRY.registerAttributes(FAKE_PLAYER, FakePlayerEntity::getHumanoidAttributes);
	}
}
