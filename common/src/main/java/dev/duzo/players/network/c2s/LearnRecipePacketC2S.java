package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.menu.FakeCrafterMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public record LearnRecipePacketC2S(int id) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("learn_recipe");

	public static LearnRecipePacketC2S decode(FriendlyByteBuf buf) {
		return new LearnRecipePacketC2S(buf.readInt());
	}

	public static void handle(PacketContext<LearnRecipePacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		ServerPlayer sender = ctx.sender();
		if (sender == null) return;
		if (!(sender.level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		if (!(sender.containerMenu instanceof FakeCrafterMenu menu) || menu.fake() != entity) return;

		ItemStack out = menu.result().getItem(0);
		if (out.isEmpty()) return; // nothing to learn

		CompoundTag outTag = out.save(new CompoundTag());

		CompoundTag recipe = new CompoundTag();
		ListTag grid = new ListTag();
		for (int i = 0; i < 9; i++) {
			ItemStack s = menu.grid().getItem(i);
			grid.add(StringTag.valueOf(s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));
		}
		recipe.put("Grid", grid);
		recipe.put("Out", outTag);
		recipe.putInt("Yield", out.getCount());

		entity.mutateAIState(s -> {
			CompoundTag params = s.jobParams().copy();
			params.put("Recipe", recipe);
			s.setJobParams(params);
		});
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
	}
}
