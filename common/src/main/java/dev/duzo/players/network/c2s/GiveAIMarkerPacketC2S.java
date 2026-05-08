package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public record GiveAIMarkerPacketC2S(int id, byte mode) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("ai_give_marker");

	public static GiveAIMarkerPacketC2S decode(FriendlyByteBuf buf) {
		return new GiveAIMarkerPacketC2S(buf.readInt(), buf.readByte());
	}

	public static void handle(PacketContext<GiveAIMarkerPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		if (!(sender.serverLevel().getEntity(ctx.message().id) instanceof FakePlayerEntity)) return;
		ItemStack stack = AIMarkerItem.make(ctx.message().id, ctx.message().mode());
		if (!sender.getInventory().add(stack)) {
			sender.drop(stack, false);
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeByte(mode);
	}
}
