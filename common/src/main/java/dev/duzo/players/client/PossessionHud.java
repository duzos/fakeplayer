package dev.duzo.players.client;

import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Drawn over the possessed fake's view: spectators get no crosshair from vanilla, and nothing else says whose eyes these are. */
public final class PossessionHud {
	private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");

	private PossessionHud() {}

	public static void render(GuiGraphics graphics, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		FakePlayerEntity fake = PossessionClient.possessed(client);
		if (fake == null || client.options.hideGui) return;

		if (client.options.getCameraType().isFirstPerson()) {
			graphics.blit(ICONS, (graphics.guiWidth() - 15) / 2, (graphics.guiHeight() - 15) / 2, 0, 0, 15, 15);
		}

		Component held = fake.getMainHandItem().isEmpty()
				? Component.translatable("hud.players.possession.empty_hand")
				: fake.getMainHandItem().getHoverName();
		Component status = Component.translatable("hud.players.possession.status", fake.getDisplayName(),
				Mth.ceil(fake.getHealth()), Mth.ceil(fake.getMaxHealth()), held);
		graphics.drawCenteredString(client.font, status, graphics.guiWidth() / 2, graphics.guiHeight() - 40, 0xFFFFFFFF);
	}
}
