package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.requests.FakePlayerRequests;
import dev.duzo.players.api.requests.RequestKey;
import dev.duzo.players.api.requests.RequesterKind;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.Job;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Drops one of the sender's own outstanding requests.
 *
 * <p>Only the item id travels. The key is rebuilt server-side from the sender, so a client cannot
 * cancel anything but its own requests no matter what it sends. Requests raised by fakes are not
 * cancellable here on purpose: a blocked fake simply re-raises within a second, so removing one
 * would look broken rather than helpful.
 */
public record CancelRequestPacketC2S(int id, String item) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("ai_cancel_request");
	private static final int MAX_ITEM_LENGTH = 256;

	public static CancelRequestPacketC2S decode(FriendlyByteBuf buf) {
		return new CancelRequestPacketC2S(buf.readInt(), buf.readUtf(MAX_ITEM_LENGTH));
	}

	public static void handle(PacketContext<CancelRequestPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		if (!(sender.level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		if (entity.getAIState().job() != Job.QUARTERMASTER) return;

		UUID owner = entity.getAIState().ownerUUID();
		if (owner == null || !owner.equals(sender.getUUID())) return;

		ResourceLocation item = ResourceLocation.tryParse(ctx.message().item().trim());
		if (item == null) return;

		RequestKey key = new RequestKey(sender.getUUID(), RequesterKind.PLAYER, Job.NONE, item);
		if (FakePlayerRequests.cancel(entity, key)) {
			sender.sendSystemMessage(Component.literal("Cancelled the request for " + item));
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(item, MAX_ITEM_LENGTH);
	}
}
