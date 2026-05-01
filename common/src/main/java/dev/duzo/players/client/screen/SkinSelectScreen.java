package dev.duzo.players.client.screen;

import commonnetwork.api.Network;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.SkinGrabber;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.network.c2s.SetSkinKeyPacketC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class SkinSelectScreen extends Screen {
	private static final ResourceLocation TEXTURE = PlayersCommon.id("textures/gui/select.png");
	private final FakePlayerEntity target;
	int bgHeight = 138;
	int bgWidth = 216;
	int left, top;
	private FakePlayerEntity render;
	private int index;
	private int sizeCache;
	private String selectedSkin;
	private boolean wasDownloading;

	public SkinSelectScreen(FakePlayerEntity target) {
		super(Component.literal("Skin Selection"));
		this.target = target;
		this.render = new FakePlayerEntity(target.level());

		this.index = SkinGrabber.INSTANCE.getAllKeys().indexOf(target.getSkinData().key());
		this.updateSelectedSkin();

		sizeCache = SkinGrabber.INSTANCE.getAllKeys().size();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
	}

	@Override
	protected void init() {
		this.top = (this.height - this.bgHeight) / 2; // this means everythings centered and scaling, same for below
		this.left = (this.width - this.bgWidth) / 2;

		super.init();

		this.addRenderableWidget(new PlainTextButton((width / 2 - 30), (height / 2 + 12),
				this.font.width("<"), 10, Component.literal("<"), button -> this.previousSkin(), this.font));
		this.addRenderableWidget(new PlainTextButton((width / 2 + 25), (height / 2 + 12),
				this.font.width(">"), 10, Component.literal(">"), button -> this.nextSkin(), this.font));
		this.addRenderableWidget(new PlainTextButton((width / 2 - this.font.width(Component.literal("SELECT")) / 2), (height / 2 + 12),
				this.font.width(Component.literal("SELECT")), 10, Component.literal("SELECT"), button -> this.selectSkin(), this.font));
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		this.drawBackground(context);

		Component currentText = Component.literal("Downloading skins...");
		if (SkinGrabber.INSTANCE.hasDownloads()) {
			context.drawString(this.font, currentText, (int) (left + (bgWidth * 0.5f)) - this.font.width(currentText) / 2,
					(int) (top + (bgHeight * 0.5)), 0xffffff, true);

			currentText = Component.literal(SkinGrabber.INSTANCE.getDownloadsRemaining() + " skins remaining");
			context.drawString(this.font, currentText, (int) (left + (bgWidth * 0.5f)) - this.font.width(currentText) / 2,
					(int) (top + (bgHeight * 0.65)), 0xffffff, true);

			wasDownloading = true;
			return;
		}

		if (wasDownloading) {
			// refresh index and size cache
			this.index = SkinGrabber.INSTANCE.getAllKeys().indexOf(this.getSelectedSkin());
			this.sizeCache = SkinGrabber.INSTANCE.getAllKeys().size();
		}

		super.render(context, mouseX, mouseY, delta);

		currentText = Component.literal(this.getSelectedSkin().length() > 11 ? this.getSelectedSkin().substring(0, 11) : this.getSelectedSkin());
		context.drawString(this.font, currentText, (int) (left + (bgWidth * 0.5f)) - this.font.width(currentText) / 2,
				(int) (top + (bgHeight * 0.5)), 0xffffff, true);

		this.renderSkin(context, (int) (left + (bgWidth * 0.5f)), (int) (top + (bgHeight * 0.45f)), mouseX, mouseY, this.getSelectedSkin());

		currentText = Component.literal((index + 1) + "/" + sizeCache);
		context.drawString(this.font, currentText, (int) (left + (bgWidth * 0.5f)) - this.font.width(currentText) / 2,
				(int) (top + (bgHeight * 0.7)), 0xffffff, true);
	}

	private String getSelectedSkin() {
		return this.selectedSkin;
	}

	private void updateSelectedSkin() {
		if (!SkinGrabber.INSTANCE.jeryn.isDownloaded()) {
			SkinGrabber.INSTANCE.jeryn.download();
		}

		if (index < 0) {
			index = 0;
		}
		this.selectedSkin = SkinGrabber.INSTANCE.getAllKeys().get(index);
	}

	private void nextSkin() {
		if (SkinGrabber.INSTANCE.hasDownloads()) return;

		index++;

		int size = SkinGrabber.INSTANCE.getAllKeys().size();
		if (index > size - 1) {
			index = size - 1;
		}

		sizeCache = size;

		this.updateSelectedSkin();
	}

	private void previousSkin() {
		if (SkinGrabber.INSTANCE.hasDownloads()) return;

		index--;
		if (index < 0) {
			// index = SkinGrabber.INSTANCE.getAllKeys().size() - 1;
			index = 0;
		}
		this.updateSelectedSkin();
	}

	private void selectSkin() {
		if (SkinGrabber.INSTANCE.hasDownloads()) return;

		Network.getNetworkHandler().sendToServer(new SetSkinKeyPacketC2S(this.target.getId(), this.getSelectedSkin(), SkinGrabber.INSTANCE.getUrl(this.getSelectedSkin())));
		this.onClose();
	}

	private void drawBackground(GuiGraphics context) {
		context.blit(TEXTURE, left, top, 0, 0, bgWidth, bgHeight);
	}

	private void renderSkin(GuiGraphics context, int x, int y, int mouseX, int mouseY, String key) {
		render.setSkin(new FakePlayerEntity.SkinData(key, key, SkinGrabber.SKIN_URL + "duzo"));

		InventoryScreen.renderEntityInInventoryFollowsMouse(
				context,
				x - 25, y - 70,
				x + 25, y,
				24,
				0.0625F,
				(float) (x - mouseX),
				(float) (y - mouseY - 24),
				this.render
		);
	}
}
