package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.api.requests.RaisedRequest;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.Job;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public record RequestItemPacketC2S(int id, String item, int count) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("ai_request_item");
	private static final int MAX_ITEM_LENGTH = 256;

	public static RequestItemPacketC2S decode(FriendlyByteBuf buf) {
		return new RequestItemPacketC2S(buf.readInt(), buf.readUtf(MAX_ITEM_LENGTH), buf.readInt());
	}

	public static void handle(PacketContext<RequestItemPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		if (!(sender.level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		// the clicked entity is validated only to confirm the menu belonged to a quartermaster; the
		// request itself goes to the nearest stocked one, so a small pool does not shadow a big one
		if (entity.getAIState().job() != Job.QUARTERMASTER) return;

		ResourceLocation id = ResourceLocation.tryParse(ctx.message().item().trim());
		Optional<Item> found = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
		if (found.isEmpty()) {
			sender.sendSystemMessage(Component.literal("No such item: " + ctx.message().item()));
			return;
		}

		int count = Math.max(1, Math.min(ItemRequest.MAX_COUNT, ctx.message().count()));
		RaisedRequest raised = FakePlayerRequests.raise(sender, new ItemStack(found.get(), count),
				FakePlayerRequests.PRIORITY_PLAYER);
		switch (raised.result()) {
			case RAISED -> sender.sendSystemMessage(Component.literal("Requested " + count + " x " + id));
			case ALREADY_OPEN -> sender.sendSystemMessage(Component.literal("Already on the way: " + id));
			case HOLDER_NOT_READY -> sender.sendSystemMessage(
					Component.literal("Already on the way: " + id + " (that quartermaster is still waking up)"));
			case NO_QUARTERMASTER -> sender.sendSystemMessage(
					Component.literal("No quartermaster of yours in range has a storage pool marked."));
			case BOARD_FULL -> sender.sendSystemMessage(
					Component.literal("That quartermaster's request board is full."));
			case INVALID -> sender.sendSystemMessage(Component.literal("That request could not be made."));
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(item, MAX_ITEM_LENGTH);
		buf.writeInt(count);
	}
}
