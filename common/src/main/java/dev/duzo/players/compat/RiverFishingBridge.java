package dev.duzo.players.compat;

import dev.duzo.players.compat.river.RiverFishingCatches;
import dev.duzo.players.compat.river.RiverRodAssembly;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.requests.RequestRouting;
import dev.duzo.players.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Only constructed with River Fishing installed. Public signatures never expose optional API types. */
public final class RiverFishingBridge {
	private final RiverRodAssembly assembly = new RiverRodAssembly();
	private RiverFishingCatches.Attempt attempt;
	private ItemStack castRod;
	private ItemStack expected = ItemStack.EMPTY;
	private String notified = "";

	public static boolean present() { return Services.PLATFORM.isModLoaded("riverfishing"); }
	public static boolean isRod(ItemStack rod) { return present() && RiverRodAssembly.isRod(rod); }
	public boolean prepare(FakePlayerEntity fake, ItemStack rod) { return assembly.prepare(fake, rod).ready(); }
	public void flight(ItemStack rod) { attempt = null; castRod = rod; expected = rod.copy(); }
	public boolean matches(ItemStack rod) {
		return rod == castRod && (attempt == null ? ItemStack.matches(expected, rod) : attempt.matches(rod));
	}
	public boolean started() { return attempt != null; }
	public long settle(ServerLevel level, FakePlayerEntity fake, ItemStack rod, BlockPos pos) {
		attempt = RiverFishingCatches.prepare(level, fake, rod, pos);
		if (attempt.status() == RiverFishingCatches.Status.READY) { notified = ""; return attempt.waitTicks(); }
		String message = attempt.message();
		if (!message.isEmpty() && !message.equals(notified)
				&& RequestRouting.notifyOwner(level, fake, message)) notified = message;
		return -1;
	}
	public int strike(ServerLevel level, FakePlayerEntity fake, ItemStack rod) { return attempt.strike(level, fake, rod); }
	public List<ItemStack> finish(ServerLevel level, FakePlayerEntity fake, ItemStack rod) { return attempt.finish(level, fake, rod); }
	public void cancel() { attempt = null; castRod = null; expected = ItemStack.EMPTY; }
	public void stop(FakePlayerEntity fake) { cancel(); assembly.stop(fake); }
	public int[] reservedSlots(FakePlayerEntity fake, ItemStack rod) { return assembly.reservedSlots(fake, rod); }
	public CompoundTag save() { return assembly.save(); }
	public void load(CompoundTag tag) { assembly.load(tag); }
}
