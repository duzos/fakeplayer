package dev.duzo.players.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import commonnetwork.api.Network;
import dev.duzo.players.Constants;
import dev.duzo.players.PlayersCommon;
import dev.duzo.players.api.LocalSkinStore;
import dev.duzo.players.api.SkinGrabber;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.network.c2s.SetSkinKeyPacketC2S;
import dev.duzo.players.network.c2s.UploadSkinPacketC2S;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SkinSelectScreen extends Screen {
	private static final Identifier TEXTURE = PlayersCommon.id("textures/gui/select.png");
	private static final Component HINT = Component.literal("shift = info").withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC);
	private final FakePlayerEntity target;
	int bgHeight = 138;
	int bgWidth = 216;
	int left, top;
	private FakePlayerEntity render;
	private int index;
	private int sizeCache;
	private String selectedSkin;
	private boolean wasDownloading;
	private String uploadStatus;
	private long uploadStatusUntil;
	private PlainTextButton prevButton;
	private PlainTextButton nextButton;
	private PlainTextButton selectButton;
	private PlainTextButton uploadButton;

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
		this.top = (this.height - this.bgHeight) / 2;
		this.left = (this.width - this.bgWidth) / 2;

		super.init();

		this.prevButton = new PlainTextButton((width / 2 - 30), (height / 2 + 12),
				this.font.width("<"), 10, Component.literal("<"), button -> this.previousSkin(), this.font);
		this.nextButton = new PlainTextButton((width / 2 + 25), (height / 2 + 12),
				this.font.width(">"), 10, Component.literal(">"), button -> this.nextSkin(), this.font);
		this.selectButton = new PlainTextButton((width / 2 - this.font.width(Component.literal("SELECT")) / 2), (height / 2 + 12),
				this.font.width(Component.literal("SELECT")), 10, Component.literal("SELECT"), button -> this.selectSkin(), this.font);
		this.addRenderableWidget(this.prevButton);
		this.addRenderableWidget(this.nextButton);
		this.addRenderableWidget(this.selectButton);

		Component upload = Component.literal("UPLOAD");
		this.uploadButton = new PlainTextButton((width / 2 - this.font.width(upload) / 2), (top + bgHeight - 26),
				this.font.width(upload), 10, upload, button -> this.uploadSkin(), this.font);
		this.addRenderableWidget(this.uploadButton);

		applyTooltips();
	}

	@Override
	public void tick() {
		super.tick();
		applyTooltips();
	}

	private static boolean shiftHeld() {
		var window = Minecraft.getInstance().getWindow();
		return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
				|| InputConstants.isKeyDown(window, 344);
	}

	private void applyTooltips() {
		boolean shift = shiftHeld();
		setTip(this.prevButton, shift, "Cycle back through downloaded trending skins.");
		setTip(this.nextButton, shift, "Cycle forward through downloaded trending skins.");
		setTip(this.selectButton, shift, "Apply the highlighted trending skin to the fake player.");
		setTip(this.uploadButton, shift, "Pick a 64x64 or 64x32 png up to 32 kb from your computer and apply it. Op-only by default.");
	}

	private static void setTip(AbstractWidget widget, boolean shift, String longText) {
		if (widget == null) return;
		widget.setTooltip(shift ? Tooltip.create(Component.literal(longText)) : null);
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		this.drawBackground(context);

		Component currentText = Component.literal("Downloading skins...");
		if (SkinGrabber.INSTANCE.hasDownloads()) {
			context.drawString(this.font, currentText, (int) (left + (bgWidth * 0.5f)) - this.font.width(currentText) / 2,
					(int) (top + (bgHeight * 0.5)), 0xFFFFFFFF, true);

			currentText = Component.literal(SkinGrabber.INSTANCE.getDownloadsRemaining() + " skins remaining");
			context.drawString(this.font, currentText, (int) (left + (bgWidth * 0.5f)) - this.font.width(currentText) / 2,
					(int) (top + (bgHeight * 0.65)), 0xFFFFFFFF, true);

			wasDownloading = true;
			return;
		}

		if (wasDownloading) {
			this.index = SkinGrabber.INSTANCE.getAllKeys().indexOf(this.getSelectedSkin());
			this.sizeCache = SkinGrabber.INSTANCE.getAllKeys().size();
		}

		super.render(context, mouseX, mouseY, delta);

		currentText = Component.literal(this.getSelectedSkin().length() > 11 ? this.getSelectedSkin().substring(0, 11) : this.getSelectedSkin());
		context.drawString(this.font, currentText, (int) (left + (bgWidth * 0.5f)) - this.font.width(currentText) / 2,
				(int) (top + (bgHeight * 0.5)), 0xFFFFFFFF, true);

		this.renderSkin(context, (int) (left + (bgWidth * 0.5f)), (int) (top + (bgHeight * 0.45f)), mouseX, mouseY, this.getSelectedSkin());

		currentText = Component.literal((index + 1) + "/" + sizeCache);
		context.drawString(this.font, currentText, (int) (left + (bgWidth * 0.5f)) - this.font.width(currentText) / 2,
				(int) (top + (bgHeight * 0.7)), 0xFFFFFFFF, true);

		if (uploadStatus != null && System.currentTimeMillis() < uploadStatusUntil) {
			Component statusText = Component.literal(uploadStatus);
			context.drawString(this.font, statusText, (int) (left + (bgWidth * 0.5f)) - this.font.width(statusText) / 2,
					top + 4, 0xFFFF5555, true);
		}

		context.drawString(this.font, HINT, left + 8, top + 12, 0xFFFFFFFF, false);
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
			index = 0;
		}
		this.updateSelectedSkin();
	}

	private void uploadSkin() {
		String picked;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(1);
			filters.put(stack.UTF8("*.png"));
			filters.flip();
			picked = TinyFileDialogs.tinyfd_openFileDialog("Select skin PNG", null, filters, "PNG image", false);
		} catch (Exception e) {
			Constants.LOG.error("File picker failed", e);
			setUploadStatus("error opening file picker");
			return;
		}
		if (picked == null) return;
		try {
			Path path = Paths.get(picked);
			byte[] bytes = Files.readAllBytes(path);
			LocalSkinStore.validate(bytes);
			String key = LocalSkinStore.hashBytes(bytes);
			SkinGrabber.INSTANCE.registerLocalBytes(key, bytes);
			Network.getNetworkHandler().sendToServer(new UploadSkinPacketC2S(this.target.getId(), key, bytes));
			this.onClose();
		} catch (LocalSkinStore.ValidationException e) {
			setUploadStatus("invalid: " + e.reason);
		} catch (Exception e) {
			Constants.LOG.error("Failed to upload skin", e);
			setUploadStatus("upload failed");
		}
	}

	private void setUploadStatus(String text) {
		this.uploadStatus = text;
		this.uploadStatusUntil = System.currentTimeMillis() + 3000L;
	}

	private void selectSkin() {
		if (SkinGrabber.INSTANCE.hasDownloads()) return;

		Network.getNetworkHandler().sendToServer(new SetSkinKeyPacketC2S(this.target.getId(), this.getSelectedSkin(), SkinGrabber.INSTANCE.getUrl(this.getSelectedSkin())));
		this.onClose();
	}

	private void drawBackground(GuiGraphics context) {
		context.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, left, top, 0, 0, bgWidth, bgHeight, 256, 256);
	}

	private void renderSkin(GuiGraphics context, int x, int y, int mouseX, int mouseY, String key) {
		render.setSkin(new FakePlayerEntity.SkinData(key, key, SkinGrabber.SKIN_URL + "duzo"));

		InventoryScreen.renderEntityInInventoryFollowsMouse(
				context,
				x - 25, y - 58,
				x + 25, y + 12,
				24,
				0.0625F,
				(float) mouseX,
				(float) mouseY,
				this.render
		);
	}
}
