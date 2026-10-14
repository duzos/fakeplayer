package dev.duzo.players.entities.possession;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** Where a possessing player goes back to, and what game mode they get back. Saved on the Possession Link, so it survives a restart. */
public record PossessionReturn(String dimension, double x, double y, double z, float yRot, float xRot, int gameMode, UUID fake) {
	public CompoundTag toTag() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Dim", dimension);
		tag.putDouble("X", x);
		tag.putDouble("Y", y);
		tag.putDouble("Z", z);
		tag.putFloat("YRot", yRot);
		tag.putFloat("XRot", xRot);
		tag.putInt("Mode", gameMode);
		tag.putIntArray("Fake", UUIDUtil.uuidToIntArray(fake));
		return tag;
	}

	@Nullable
	public static PossessionReturn fromTag(@Nullable CompoundTag tag) {
		if (tag == null || !tag.contains("Dim")) return null;
		int[] fake = tag.getIntArray("Fake").filter(arr -> arr.length == 4).orElse(null);
		if (fake == null) return null;
		return new PossessionReturn(tag.getStringOr("Dim", ""), tag.getDoubleOr("X", 0.0), tag.getDoubleOr("Y", 0.0),
				tag.getDoubleOr("Z", 0.0), tag.getFloatOr("YRot", 0.0F), tag.getFloatOr("XRot", 0.0F),
				tag.getIntOr("Mode", 0), UUIDUtil.uuidFromIntArray(fake));
	}
}
