package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.LocalSkinStore;
import dev.duzo.players.config.PlayersConfig;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public record UploadSkinPacketC2S(int id, String key, byte[] data) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("upload_skin");

	public static UploadSkinPacketC2S decode(FriendlyByteBuf buf) {
		int id = buf.readInt();
		String key = buf.readUtf();
		byte[] data = buf.readByteArray(LocalSkinStore.MAX_BYTES + 1024);
		return new UploadSkinPacketC2S(id, key, data);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(key);
		buf.writeByteArray(data);
	}

	public static void handle(PacketContext<UploadSkinPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		try {
			if (PlayersConfig.get().allowLocalSkinUploadOpOnly
					&& !sender.hasPermissions(Commands.LEVEL_GAMEMASTERS)) {
				sender.sendSystemMessage(Component.translatable("players.upload.error.permission"));
				return;
			}

			if (!(sender.serverLevel().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) {
				Constants.LOG.error("Invalid entity id: {}", ctx.message().id);
				return;
			}

			byte[] bytes = ctx.message().data();
			LocalSkinStore.validate(bytes);

			String key = LocalSkinStore.hashBytes(bytes);

			LocalSkinStore.INSTANCE.save(key, bytes);

			String name = entity.getSkinData().name();
			entity.setSkin(new FakePlayerEntity.SkinData(name, key, LocalSkinStore.urlForKey(key)));
		} catch (LocalSkinStore.ValidationException e) {
			sender.sendSystemMessage(Component.translatable("players.upload.error." + e.reason));
		} catch (Exception e) {
			Constants.LOG.error("Failed to handle skin upload", e);
		}
	}
}
