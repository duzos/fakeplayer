package dev.duzo.players.client.screen;

import commonnetwork.api.Network;
import dev.duzo.players.menu.FakeCrafterMenu;
import dev.duzo.players.network.c2s.LearnRecipePacketC2S;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

public class FakeCrafterScreen extends AbstractContainerScreen<FakeCrafterMenu> {
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/crafting_table.png");

	public FakeCrafterScreen(FakeCrafterMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
	}

	@Override
	protected void init() {
		super.init();
		Button learn = Button.builder(Component.literal("Learn"), b -> learn())
				.bounds(this.leftPos + 58, this.topPos + this.imageHeight + 3, 60, 18)
				.build();
		this.addRenderableWidget(learn);
	}

	private void learn() {
		if (this.menu.fake() == null) return;
		Network.getNetworkHandler().sendToServer(new LearnRecipePacketC2S(this.menu.fake().getId()));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(ctx, mouseX, mouseY, partialTick);
		ctx.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, 256, 256);
	}
}
