package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

public interface JobExecutor {
	void tick(ServerLevel level, FakePlayerEntity entity);
	void onPause(FakePlayerEntity entity);
	void onResume(FakePlayerEntity entity);
	CompoundTag serialize();
	void deserialize(CompoundTag tag);
}
