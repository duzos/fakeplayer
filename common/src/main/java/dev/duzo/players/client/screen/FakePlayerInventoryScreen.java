package dev.duzo.players.client.screen;

import commonnetwork.api.Network;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.menu.FakePlayerMenu;
import dev.duzo.players.network.c2s.ApplyFakePlayerSkinPacketC2S;
import dev.duzo.players.network.c2s.CyclePosePacketC2S;
import dev.duzo.players.network.c2s.SetFakePlayerNamePacketC2S;
import dev.duzo.players.network.c2s.ToggleFakePlayerFlagPacketC2S;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

public class FakePlayerInventoryScreen extends AbstractContainerScreen<FakePlayerMenu> {
	private static final Identifier INVENTORY_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/inventory.png");

	private static final int FP_PANEL_W = 176;
	private static final int FP_PANEL_H = 166;
	private static final int PLAYER_PANEL_W = 176;
	private static final int PLAYER_PANEL_H = 84;
	private static final int PLAYER_PANEL_OFFSET_Y = 172;
	private static final int GUI_BODY_COLOUR = 0xFFC6C6C6;

	private static final Component HINT = Component.literal("shift = info").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);

	private EditBox nameEdit;
	private Button applyButton;
	private Button selectButton;
	private Button poseButton;
	private Button aiButton;
	private Button slimToggle;
	private Button tagToggle;

	private Boolean lastShiftDown;

	public FakePlayerInventoryScreen(FakePlayerMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);

		this.imageWidth = FP_PANEL_W;
		this.imageHeight = 256;
		this.titleLabelX = 86;
		this.titleLabelY = 30;
	}

	@Override
	protected void init() {
		super.init();
		FakePlayerEntity entity = this.menu.getEntity();
		if (entity == null) return;

		int x0 = this.leftPos;
		int y0 = this.topPos;

		this.nameEdit = new EditBox(this.font, x0 + 80, y0 + 8, 89, 14, Component.literal("name"));
		this.nameEdit.setMaxLength(16);
		this.nameEdit.setValue(entity.getSkinData().name());
		this.nameEdit.setResponder(this::onNameChanged);
		this.addRenderableWidget(this.nameEdit);

		this.applyButton = Button.builder(Component.literal("Apply"), b -> applySkin())
				.bounds(x0 + 80, y0 + 24, 41, 14).build();
		this.selectButton = Button.builder(Component.literal("Select"), b -> openSkinSelect())
				.bounds(x0 + 124, y0 + 24, 45, 14).build();
		this.addRenderableWidget(this.applyButton);
		this.addRenderableWidget(this.selectButton);

		this.poseButton = Button.builder(poseLabel(entity), b -> cyclePose())
				.bounds(x0 + 80, y0 + 40, 89, 14).build();
		this.addRenderableWidget(this.poseButton);

		this.aiButton = Button.builder(Component.literal("AI").withStyle(ChatFormatting.AQUA), b -> openAiMenu())
				.bounds(x0 + 98, y0 + 56, 22, 14).build();
		this.slimToggle = Button.builder(toggleLabel("SL", entity.isSlim()), b -> toggleFlag(ToggleFakePlayerFlagPacketC2S.FLAG_SLIM, !entity.isSlim()))
				.bounds(x0 + 122, y0 + 56, 22, 14).build();
		this.tagToggle = Button.builder(toggleLabel("TG", entity.isCustomNameVisible()), b -> toggleFlag(ToggleFakePlayerFlagPacketC2S.FLAG_NAMETAG, !entity.isCustomNameVisible()))
				.bounds(x0 + 146, y0 + 56, 22, 14).build();
		this.addRenderableWidget(this.aiButton);
		this.addRenderableWidget(this.slimToggle);
		this.addRenderableWidget(this.tagToggle);

		this.lastShiftDown = null;
		refreshTooltipsIfShiftChanged();
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		FakePlayerEntity entity = this.menu.getEntity();
		if (entity == null) return;
		if (this.poseButton != null) this.poseButton.setMessage(poseLabel(entity));
		if (this.slimToggle != null) this.slimToggle.setMessage(toggleLabel("SL", entity.isSlim()));
		if (this.tagToggle != null) this.tagToggle.setMessage(toggleLabel("TG", entity.isCustomNameVisible()));
		refreshTooltipsIfShiftChanged();
	}

	private static boolean shiftHeld() {
		var window = Minecraft.getInstance().getWindow();
		return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
				|| InputConstants.isKeyDown(window, 344);
	}

	private void refreshTooltipsIfShiftChanged() {
		boolean shift = shiftHeld();
		if (this.lastShiftDown != null && this.lastShiftDown == shift) return;
		this.lastShiftDown = shift;
		setTip(this.nameEdit, shift, "Name", "Live-updates the entity name. Skin only changes when you press Apply.");
		setTip(this.applyButton, shift, "Apply skin", "Re-fetches and applies the skin matching the typed name.");
		setTip(this.selectButton, shift, "Skin gallery", "Opens the skin selector for browsing downloaded skins.");
		setTip(this.poseButton, shift, "Pose", "Cycles standing → sitting → laying.");
		setTip(this.aiButton, shift, "AI menu", "Bond, jobs, waypoint/region/deposit markers, no-ai toggle.");
		setTip(this.slimToggle, shift, "Slim", "Switches between the slim (Alex) and classic (Steve) model.");
		setTip(this.tagToggle, shift, "Nametag", "Toggles the floating nametag above the entity.");
	}

	private static void setTip(AbstractWidget widget, boolean shift, String shortText, String longText) {
		if (widget == null) return;
		widget.setTooltip(Tooltip.create(Component.literal(shift ? longText : shortText)));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.nameEdit != null && this.nameEdit.isFocused()) {
			this.nameEdit.keyPressed(event);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (this.nameEdit != null && this.nameEdit.isFocused()) {
			this.nameEdit.charTyped(event);
			return true;
		}
		return super.charTyped(event);
	}

	private static Component poseLabel(FakePlayerEntity entity) {
		String state = switch (entity.getPhysicalState()) {
			case STANDING -> "Standing";
			case SITTING -> "Sitting";
			case LAYING -> "Laying";
		};
		return Component.literal("Pose: " + state);
	}

	private static Component toggleLabel(String text, boolean on) {
		return Component.literal(text).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private void onNameChanged(String value) {
		FakePlayerEntity entity = this.menu.getEntity();
		if (entity == null) return;
		Network.getNetworkHandler().sendToServer(new SetFakePlayerNamePacketC2S(entity.getId(), value));
	}

	private void applySkin() {
		FakePlayerEntity entity = this.menu.getEntity();
		if (entity == null) return;
		Network.getNetworkHandler().sendToServer(new ApplyFakePlayerSkinPacketC2S(entity.getId(), this.nameEdit.getValue()));
	}

	private void openSkinSelect() {
		FakePlayerEntity entity = this.menu.getEntity();
		if (entity == null) return;
		Minecraft.getInstance().setScreen(new SkinSelectScreen(entity));
	}

	private void cyclePose() {
		FakePlayerEntity entity = this.menu.getEntity();
		if (entity == null) return;
		Network.getNetworkHandler().sendToServer(new CyclePosePacketC2S(entity.getId()));
	}

	private void openAiMenu() {
		FakePlayerEntity entity = this.menu.getEntity();
		if (entity == null) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) minecraft.player.closeContainer();
		minecraft.setScreen(new AISubMenuScreen(entity));
	}

	private void toggleFlag(byte flag, boolean newValue) {
		FakePlayerEntity entity = this.menu.getEntity();
		if (entity == null) return;
		Network.getNetworkHandler().sendToServer(new ToggleFakePlayerFlagPacketC2S(entity.getId(), flag, newValue));
	}

	@Override
	protected void renderBg(GuiGraphics ctx, float partialTick, int mouseX, int mouseY) {
		int x = this.leftPos;
		int y = this.topPos;

		ctx.blit(RenderPipelines.GUI_TEXTURED, INVENTORY_TEXTURE, x, y, 0, 0, FP_PANEL_W, FP_PANEL_H, 256, 256);
		ctx.fill(x + 86, y + 15, x + 175, y + 60, GUI_BODY_COLOUR);
		ctx.blit(RenderPipelines.GUI_TEXTURED, INVENTORY_TEXTURE, x + 169, y + 15, 169, 15, 7, 45, 256, 256);
		ctx.blit(RenderPipelines.GUI_TEXTURED, INVENTORY_TEXTURE, x, y + PLAYER_PANEL_OFFSET_Y, 0, 82, PLAYER_PANEL_W, PLAYER_PANEL_H, 256, 256);

		FakePlayerEntity entity = this.menu.getEntity();
		if (entity != null) {
			InventoryScreen.renderEntityInInventoryFollowsMouse(
					ctx,
					x + 26, y + 8,
					x + 75, y + 78,
					30,
					0.0625F,
					(float) mouseX,
					(float) mouseY,
					entity
			);
		}
	}

	@Override
	protected void renderLabels(GuiGraphics ctx, int mouseX, int mouseY) {
		// Centred horizontally with the toggle row (x = 98..168, centre = 133)
		int hintX = 133 - this.font.width(HINT) / 2;
		ctx.drawString(this.font, HINT, hintX, 71, 0x404040, false);
	}
}
