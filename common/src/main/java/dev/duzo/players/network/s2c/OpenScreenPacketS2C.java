package dev.duzo.players.network.s2c;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.client.screen.SkinSelectScreen;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public record OpenScreenPacketS2C(int id, CompoundTag data) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("open_screen");

	public static OpenScreenPacketS2C decode(FriendlyByteBuf buf) {
		return new OpenScreenPacketS2C(buf.readInt(), buf.readNbt());
	}

	public static void handle(PacketContext<OpenScreenPacketS2C> ctx) {
		if (Side.CLIENT.equals(ctx.side())) {
			try {
				// Open screen
				Screen screen = ScreenLookup.values()[ctx.message().id].supplier.apply(ctx.message().data);
				Minecraft.getInstance().setScreen(screen);
			} catch (Exception ignored) {
			}
		}
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeNbt(data);
	}

	public enum ScreenLookup {
		SKIN_SELECT((data) -> new SkinSelectScreen((FakePlayerEntity) Minecraft.getInstance().level.getEntity(data.getIntOr("EntityId", -1))));

		public final Function<CompoundTag, Screen> supplier;

		ScreenLookup(Function<CompoundTag, Screen> supplier) {
			this.supplier = supplier;
		}
	}
}
