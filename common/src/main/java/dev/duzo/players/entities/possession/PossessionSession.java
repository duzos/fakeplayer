package dev.duzo.players.entities.possession;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.UUID;

/**
 * Server-side state of one player in one fake's body. Only touched on the server thread. The shell and the body are held
 * weakly and found again by UUID, because either can be unloaded and reloaded while the possession goes on.
 */
final class PossessionSession {
	final UUID player;
	final UUID fakeId;
	final UUID bodyId;
	final long epoch;
	final long startTick;
	final String fakeName;

	private WeakReference<FakePlayerEntity> fake = new WeakReference<>(null);

	/** Where the body was last seen, so a body that is moved, or unloaded, is still the place to come back to. */
	String bodyDimension;
	Vec3 bodyPos;
	float bodyYaw;
	float bodyPitch;

	PossessionSession(UUID player, UUID fakeId, UUID bodyId, long epoch, long startTick, String fakeName,
	                  String bodyDimension, Vec3 bodyPos, float bodyYaw, float bodyPitch) {
		this.player = player;
		this.fakeId = fakeId;
		this.bodyId = bodyId;
		this.epoch = epoch;
		this.startTick = startTick;
		this.fakeName = fakeName;
		this.bodyDimension = bodyDimension;
		this.bodyPos = bodyPos;
		this.bodyYaw = bodyYaw;
		this.bodyPitch = bodyPitch;
	}

	@Nullable
	FakePlayerEntity fake() {
		FakePlayerEntity f = fake.get();
		return f == null || f.isRemoved() ? null : f;
	}

	void bindFake(FakePlayerEntity f) {
		fake = new WeakReference<>(f);
	}
}
