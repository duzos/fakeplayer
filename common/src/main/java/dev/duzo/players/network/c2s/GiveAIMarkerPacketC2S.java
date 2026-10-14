package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public record GiveAIMarkerPacketC2S(int id, byte mode, byte slot) {
	public static final Identifier LOCATION = PlayersCommon.id("ai_give_marker");

	public GiveAIMarkerPacketC2S(int id, byte mode) {
		this(id, mode, AIMarkerItem.CHEST_SLOT_DEPOSIT);
	}

	public static GiveAIMarkerPacketC2S decode(FriendlyByteBuf buf) {
		return new GiveAIMarkerPacketC2S(buf.readInt(), buf.readByte(), buf.readByte());
	}

	public static void handle(PacketContext<GiveAIMarkerPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		if (!(sender.level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		// an owned fake takes markers from its owner only; the anchor needs an owner, since the build is paid from theirs
		UUID owner = entity.getAIState().ownerUUID();
		boolean anchor = ctx.message().mode() == AIMarkerItem.PURPOSE_ANCHOR;
		if (owner == null ? anchor : !owner.equals(sender.getUUID())) return;
		AIMarkerItem.clearAllFor(sender);
		ItemStack stack = AIMarkerItem.make(entity, sender, ctx.message().mode(), ctx.message().slot(), sender.level().getGameTime());
		// Issue #28 asked for offhand-then-hotbar-then-drop placement, but writing to offhand
		// directly desynced with the client's F-swap prediction (duped a phantom marker the
		// server didn't have). Falling back to vanilla Inventory#add until the placement is
		// reliable across loaders.
		if (!sender.getInventory().add(stack)) {
			sender.drop(stack, false);
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeByte(mode);
		buf.writeByte(slot);
	}
}
