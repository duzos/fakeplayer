package dev.duzo.players.core;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public final class SessionItemSweeper {
	private static final int INTERVAL_TICKS = 10;

	private SessionItemSweeper() {}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % INTERVAL_TICKS != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sweep(player);
		}
	}

	private static void sweep(ServerPlayer player) {
		long now = player.level().getGameTime();
		AIMarkerItem.sweepSessionItems(player, stack -> shouldRemove(player, stack, now));
	}

	private static boolean shouldRemove(ServerPlayer player, ItemStack stack, long now) {
		UUID owner = AIMarkerItem.ownerUUID(stack);
		if (owner == null || !owner.equals(player.getUUID())) return true;
		if (now >= AIMarkerItem.expiresAt(stack)) return true;
		UUID fakeId = AIMarkerItem.fakeUUID(stack);
		if (fakeId == null) return true;
		Entity bound = findEntity(player.getServer(), fakeId);
		if (!(bound instanceof FakePlayerEntity entity)) return true;
		if (entity.level() != player.level()) return true;
		return entity.distanceToSqr(player) > AIMarkerItem.SESSION_RANGE_SQ;
	}

	private static Entity findEntity(MinecraftServer server, UUID uuid) {
		for (ServerLevel level : server.getAllLevels()) {
			Entity e = level.getEntity(uuid);
			if (e != null) return e;
		}
		return null;
	}
}
