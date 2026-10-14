package dev.duzo.players.entities.ai.builder;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.AIState;
import dev.duzo.players.entities.ai.BuilderJobExecutor;
import dev.duzo.players.entities.ai.claims.WorkClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import javax.annotation.Nullable;

/** What a Builder is told to build, kept in jobParams so it syncs to the menu. Writing it restarts the job. */
/** `uploader` is the player whose upload folder the schematic is looked up in, fixed when it is named. */
public record BuilderParams(String schematic, @Nullable BlockPos anchor, Rotation rotation, Mirror mirror, boolean clear, String uploader) {
	public static final int MAX_NAME = 64;

	public static BuilderParams read(AIState s) {
		CompoundTag t = s.jobParams();
		BlockPos anchor = t.contains("Anchor") ? BlockPos.of(t.getLong("Anchor")) : null;
		return new BuilderParams(t.getString("Schematic"), anchor,
				byOrdinal(Rotation.values(), t.getInt("Rot"), Rotation.NONE),
				byOrdinal(Mirror.values(), t.getInt("Mirror"), Mirror.NONE),
				!t.contains("Clear") || t.getBoolean("Clear"), t.getString("Uploader"));
	}

	public BuilderParams withAnchor(BlockPos pos, Rotation rot, String uploader) {
		return new BuilderParams(schematic, pos, rot, mirror, clear, uploader);
	}

	public boolean ready() {
		return !schematic.isBlank() && anchor != null;
	}

	/** Replaces the params and drops the job state, because progress in the old build means nothing in the new. */
	public void write(FakePlayerEntity entity) {
		// the old build's requests would otherwise be left on a quartermaster's board for ever
		BuilderJobExecutor.cancelOutstanding(entity);
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
			t.putString("Uploader", uploader);
			s.setJobParams(t);
			s.setJobState(new CompoundTag());
		});
	}

	private static <E> E byOrdinal(E[] all, int i, E fallback) {
		return i >= 0 && i < all.length ? all[i] : fallback;
	}
}
