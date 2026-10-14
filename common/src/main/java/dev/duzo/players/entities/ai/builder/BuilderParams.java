package dev.duzo.players.entities.ai.builder;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.AIState;
import dev.duzo.players.entities.ai.claims.WorkClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

/** What a Builder is told to build, kept in jobParams so it syncs to the menu. Writing it restarts the job. */
public record BuilderParams(String schematic, @Nullable BlockPos anchor, Rotation rotation, Mirror mirror, boolean clear) {
	public static final int MAX_NAME = 64;

	public static BuilderParams read(AIState s) {
		CompoundTag t = s.jobParams();
		BlockPos anchor = t.getLong("Anchor").map(BlockPos::of).orElse(null);
		return new BuilderParams(t.getStringOr("Schematic", ""), anchor,
				byOrdinal(Rotation.values(), t.getIntOr("Rot", 0), Rotation.NONE),
				byOrdinal(Mirror.values(), t.getIntOr("Mirror", 0), Mirror.NONE),
				t.getBooleanOr("Clear", true));
	}

	public BuilderParams withAnchor(BlockPos pos, Rotation rot) {
		return new BuilderParams(schematic, pos, rot, mirror, clear);
	}

	public boolean ready() {
		return !schematic.isBlank() && anchor != null;
	}

	/** Replaces the params and drops the job state, because progress in the old build means nothing in the new. */
	public void write(FakePlayerEntity entity) {
		entity.resetJobExecutor();
		WorkClaims.releaseAll(entity.getUUID());
		entity.mutateAIState(s -> {
			CompoundTag t = s.jobParams().copy();
			t.putString("Schematic", schematic);
			if (anchor != null) t.putLong("Anchor", anchor.asLong());
			else t.remove("Anchor");
			t.putInt("Rot", rotation.ordinal());
			t.putInt("Mirror", mirror.ordinal());
			t.putBoolean("Clear", clear);
			s.setJobParams(t);
			s.setJobState(new CompoundTag());
		});
	}

	private static <E> E byOrdinal(E[] all, int i, E fallback) {
		return i >= 0 && i < all.length ? all[i] : fallback;
	}
}
