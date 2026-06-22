package dev.duzo.players.network.c2s;

import commonnetwork.networking.data.PacketContext;
import commonnetwork.networking.data.Side;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SetAIFilterPacketC2S(int id, String tagName) {
	public static final ResourceLocation LOCATION = PlayersCommon.id("ai_set_filter");
	private static final String DEFAULT_FILTER = "c:ores";
	private static final int MAX_FILTER_LENGTH = 512;

	public static SetAIFilterPacketC2S decode(FriendlyByteBuf buf) {
		return new SetAIFilterPacketC2S(buf.readInt(), buf.readUtf(MAX_FILTER_LENGTH));
	}

	public static void handle(PacketContext<SetAIFilterPacketC2S> ctx) {
		if (!Side.SERVER.equals(ctx.side())) return;
		if (ctx.sender() == null) return;
		if (!(ctx.sender().level().getEntity(ctx.message().id) instanceof FakePlayerEntity entity)) return;
		String name = normalize(ctx.message().tagName());
		entity.mutateAIState(s -> {
			CompoundTag filter = s.filter().copy();
			filter.putString("Tag", name);
			s.setFilter(filter);
			s.setJobState(new CompoundTag());
		});
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(id);
		buf.writeUtf(tagName, MAX_FILTER_LENGTH);
	}

	private static String normalize(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) return DEFAULT_FILTER;
		StringBuilder result = new StringBuilder();
		for (String part : value.split(",")) {
			String token = part.trim();
			if (token.isEmpty()) continue;
			boolean tag = token.startsWith("#");
			String name = tag ? token.substring(1).trim() : token;
			if (ResourceLocation.tryParse(name) == null) continue;
			if (!result.isEmpty()) result.append(',');
			if (tag) result.append('#');
			result.append(name);
		}
		return result.isEmpty() ? DEFAULT_FILTER : result.toString();
	}
}
