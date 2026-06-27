package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.menu.FakeCrafterMenuProvider;
import dev.duzo.players.platform.Services;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

public record OpenCrafterLearnPacketC2S(int id) {
	public static final Identifier LOCATION = PlayersCommon.id("open_crafter_learn");

	public static OpenCrafterLearnPacketC2S decode(FriendlyByteBuf buf) {
		return new OpenCrafterLearnPacketC2S(buf.readInt());
	}

	public static void handle(PacketContext<OpenCrafterLearnPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		if (!(sender.level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		Services.COMMON_REGISTRY.openMenu(sender, new FakeCrafterMenuProvider(entity), buf -> buf.writeInt(entity.getId()));
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
	}
}
