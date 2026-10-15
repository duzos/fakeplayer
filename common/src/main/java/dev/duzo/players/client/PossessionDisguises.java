package dev.duzo.players.client;

import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.network.s2c.PossessionDisguiseS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The players this client has been told are inside a fake's body, and the skin and name to draw them with. Read by the skin
 * and name tag mixins, so everything that draws that player, including their own first-person arm and the tab list head,
 * shows the fake.
 */
public final class PossessionDisguises {
	private record Disguise(String name, PlayerSkin skin) {}

	private static final Map<UUID, Disguise> DISGUISES = new HashMap<>();
	/** Set while the body renderer asks for a player's real skin, so its own mixin does not hand back the disguise. */
	private static boolean realSkinWanted;

	private PossessionDisguises() {}

	public static void init() {
		PossessionDisguiseS2C.setHandler(packet -> Minecraft.getInstance().execute(() -> apply(packet)));
	}

	private static void apply(PossessionDisguiseS2C packet) {
		if (!packet.on()) {
			DISGUISES.remove(packet.player());
			return;
		}
		FakePlayerEntity.SkinData data = new FakePlayerEntity.SkinData(packet.skinName(), packet.skinKey(), packet.skinUrl());
		PlayerSkin skin = PlayerSkin.insecure(new ClientAsset.DownloadedTexture(data.getSkin(), ""), null, null,
				packet.slim() ? PlayerModelType.SLIM : PlayerModelType.WIDE);
		DISGUISES.put(packet.player(), new Disguise(packet.fakeName(), skin));
	}

	public static void clear() {
		DISGUISES.clear();
	}

	@Nullable
	public static PlayerSkin skinFor(UUID player) {
		if (realSkinWanted) return null;
		Disguise disguise = DISGUISES.get(player);
		return disguise == null ? null : disguise.skin;
	}

	@Nullable
	public static String nameFor(UUID player) {
		Disguise disguise = DISGUISES.get(player);
		return disguise == null ? null : disguise.name;
	}

	public static boolean isLocalDisguised() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && DISGUISES.containsKey(player.getUUID());
	}

	/** A player's own skin, ignoring any disguise: what the body they left behind is drawn with. */
	public static PlayerSkin realSkin(PlayerInfo info) {
		realSkinWanted = true;
		try {
			return info.getSkin();
		} finally {
			realSkinWanted = false;
		}
	}
}
