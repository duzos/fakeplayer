package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
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
		if (!(sender.serverLevel().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		AIMarkerItem.clearAllFor(sender);
		ItemStack stack = AIMarkerItem.make(entity, sender, ctx.message().mode(), sender.serverLevel().getGameTime());
		giveSessionItem(sender, stack);
	}

	private static void giveSessionItem(ServerPlayer player, ItemStack stack) {
		Inventory inv = player.getInventory();
		boolean placed = false;
		if (player.getOffhandItem().isEmpty()) {
			inv.setItem(40, stack);
			placed = true;
		} else {
			for (int i = 0; i < 9; i++) {
				if (inv.getItem(i).isEmpty()) {
					inv.setItem(i, stack);
					placed = true;
					break;
				}
			}
		}
		if (!placed) {
			player.drop(stack, false);
			return;
		}
		player.inventoryMenu.broadcastChanges();
		if (player.containerMenu != player.inventoryMenu) {
			player.containerMenu.broadcastChanges();
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeByte(mode);
	}
}
