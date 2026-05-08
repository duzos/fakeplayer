package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

public class NoopJobExecutor implements JobExecutor {
	@Override public void tick(ServerLevel level, FakePlayerEntity entity) {}
	@Override public void onPause(FakePlayerEntity entity) {}
	@Override public void onResume(FakePlayerEntity entity) {}
	@Override public CompoundTag serialize() { return new CompoundTag(); }
	@Override public void deserialize(CompoundTag tag) {}
}
