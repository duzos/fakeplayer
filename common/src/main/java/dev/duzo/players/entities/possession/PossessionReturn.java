package dev.duzo.players.entities.possession;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import javax.annotation.Nullable;

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
		int[] fake = tag.getIntArray("Fake");
		if (fake.length != 4) return null;
		return new PossessionReturn(tag.getString("Dim"), tag.getDouble("X"), tag.getDouble("Y"),
				tag.getDouble("Z"), tag.getFloat("YRot"), tag.getFloat("XRot"),
				tag.getInt("Mode"), UUIDUtil.uuidFromIntArray(fake));
	}
}
