package dev.duzo.players.entities.possession;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.builder.BlockBreaker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** Server-side state of one player controlling one fake. Only touched on the server thread. */
final class PossessionSession {
	final MinecraftServer server;
	final UUID player;
	final FakePlayerEntity fake;
	final String fakeName;
	final PossessionReturn returnPoint;
	final GameType returnMode;
	final long startTick;

	/** Set by the first input packet, which is how the server learns the client is looking through the fake. */
	boolean acked;
	long lastInputTick;
	int flags;

	final BlockBreaker breaker = new BlockBreaker();
	@Nullable
	BlockPos breakPos;
	long lastAttackTick = Long.MIN_VALUE / 2;
	long lastUseTick = Long.MIN_VALUE / 2;
	boolean fullNotified;
	long actionTick = Long.MIN_VALUE / 2;
	int actionCount;

	PossessionSession(MinecraftServer server, UUID player, FakePlayerEntity fake, PossessionReturn returnPoint, GameType returnMode) {
		this.server = server;
		this.player = player;
		this.fake = fake;
		this.fakeName = fake.getSkinData().name();
		this.returnPoint = returnPoint;
		this.returnMode = returnMode;
		this.startTick = server.getTickCount();
	}
}
