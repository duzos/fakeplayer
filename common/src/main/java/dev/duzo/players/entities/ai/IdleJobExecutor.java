package dev.duzo.players.entities.ai;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

public class IdleJobExecutor implements JobExecutor {
	private static final double ARRIVAL_DIST_SQR = 4.0;
	private static final double SPEED = 1.0;

	@Override
	public void tick(ServerLevel level, FakePlayerEntity entity) {
		BlockPos home = entity.getAIState().waypoint();
		if (home == null) return;
		if (entity.blockPosition().distSqr(home) <= ARRIVAL_DIST_SQR) return;
		if (!entity.getNavigation().isDone()) return;
		entity.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, SPEED);
	}

	@Override
	public void onPause(FakePlayerEntity entity) {
		entity.getNavigation().stop();
	}

	@Override public void onResume(FakePlayerEntity entity) {}
	@Override public CompoundTag serialize() { return new CompoundTag(); }
	@Override public void deserialize(CompoundTag tag) {}
}
