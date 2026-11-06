package dev.duzo.players.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import commonnetwork.api.Network;
import dev.duzo.players.client.preview.SchematicPreviewModel;
import dev.duzo.players.client.preview.SchematicPreviewRenderer;
import dev.duzo.players.core.FPJobs;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.builder.BuilderParams;
import dev.duzo.players.entities.ai.builder.SchematicPreview;
import dev.duzo.players.network.c2s.RequestSchematicListC2S;
import dev.duzo.players.network.c2s.RequestSchematicPreviewC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chooses a Builder's schematic from the files the server offers, with a spinning preview of the
 * highlighted one. The search box doubles as the name, so a namespaced structure id that is not a
 * file can still be typed; the server resolves it for the preview the same way it will for the build.
 */
public class SchematicPickerScreen extends Screen {
	private static final int PADDING = 10;
	private static final int TITLE_H = 24;
	private static final int ROW_H = 12;
	private static final int LIST_W = 120;
	private static final int BTN_H = 16;
	private static final int INFO_H = 44;

	private static final int COL_PANEL = 0xFF11171F;
	private static final int COL_PANEL_BORDER = 0xFF2A3548;
	private static final int COL_TITLE_BAR = 0xFF161C24;
	private static final int COL_ACCENT = 0xFF2EC4FF;
	private static final int COL_BODY = 0xFFE6EAEF;
	private static final int COL_MUTED = 0xFF8E97A4;
	private static final int COL_CELL = 0xFF1B2430;
	private static final int COL_CELL_HOVER = 0xFF2C3D50;
	private static final int COL_CELL_PICKED = 0xFF1F4A66;
	private static final int COL_RED = 0xFFE76060;

	private static final long TYPING_DEBOUNCE_MILLIS = 400;
	/** Above the server's own 250 ms floor, so a request is never the one it silently drops. */
	private static final long MIN_REQUEST_GAP_MILLIS = 300;
	private static final long REQUEST_TIMEOUT_MILLIS = 3000;
	private static final long DEGREES_PER_SECOND = 20;
	private static final long DOUBLE_CLICK_MILLIS = 250;

	private record Preview(SchematicPreviewModel model, String error) {}

	private final AISubMenuScreen parent;
	private final FakePlayerEntity entity;
	private final String initial;
	private final long openedAt = System.currentTimeMillis();

	private EditBox search;
	private FlatButton confirmButton;
	private FlatButton cancelButton;

	private List<String> names = List.of();
	private List<String> shown = List.of();
	private boolean listLoaded;
	private boolean listTruncated;
	private String filter = "";
	private String selected;
	private int scroll;

	private static final int CACHE_SIZE = 16;
	private final Map<String, Preview> previews = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Preview> eldest) {
			return size() > CACHE_SIZE;
		}
	};
	private int lastClickRow = -1;
	private long lastClickAt;
	private String wanted = "";
	private long wantedAt;
	private String inflight;
	private long inflightSince;
	private long lastSent;

	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int listX;
	private int listY;
	private int listH;
	private int previewX;
	private int previewY;
	private int previewW;
	private int previewH;

	public SchematicPickerScreen(AISubMenuScreen parent, FakePlayerEntity entity, String current) {
		super(Component.literal("Choose schematic"));
		this.parent = parent;
		this.entity = entity;
		this.initial = current;
	}

	public int entityId() {
		return entity.getId();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	protected void init() {
		super.init();
		if (entity == null) {
			Minecraft.getInstance().setScreen(parent);
			return;
		}
		panelW = Math.min(340, this.width - 8);
		panelH = Math.min(230, this.height - 8);
		panelX = (this.width - panelW) / 2;
		panelY = (this.height - panelH) / 2;
		int contentTop = panelY + TITLE_H + 8;
		int buttonY = panelY + panelH - PADDING - BTN_H;
		int contentBottom = buttonY - 8;

		listX = panelX + PADDING;
		int searchY = contentTop;
		listY = searchY + BTN_H + 4;
		listH = Math.max(ROW_H, contentBottom - listY);
		previewX = listX + LIST_W + 8;
		previewY = contentTop;
		previewW = Math.max(40, panelX + panelW - PADDING - previewX);
		previewH = Math.max(40, contentBottom - contentTop - INFO_H);

		String typed = search == null ? initial : search.getValue();
		search = new EditBox(this.font, listX, searchY, LIST_W, BTN_H, Component.literal("schematic"));
		search.setMaxLength(BuilderParams.MAX_NAME);
		search.setHint(Component.literal("Search or type a name"));
		search.setValue(typed);
		search.setResponder(this::onSearchChanged);
		this.addRenderableWidget(search);

		confirmButton = new FlatButton(panelX + panelW - PADDING - 70, buttonY, 70, BTN_H,
				Component.literal("Confirm"), this::confirm).bold()
				.withBg(0xFF1B3A28, 0xFF265A3A).withBorder(0xFF2F5A40, 0xFF54E08C);
		this.addRenderableWidget(confirmButton);
		cancelButton = new FlatButton(confirmButton.getX() - 4 - 60, buttonY, 60, BTN_H,
				Component.literal("Cancel"), this::onClose);
		this.addRenderableWidget(cancelButton);
		this.setInitialFocus(search);

		refilter();
		if (!listLoaded) Network.getNetworkHandler().sendToServer(new RequestSchematicListC2S(entity.getId()));
	}

	public void onList(List<String> received, boolean truncated) {
		this.names = List.copyOf(received);
		this.listTruncated = truncated;
		this.listLoaded = true;
		refilter();
		String current = target();
		int at = shown.indexOf(current);
		if (at >= 0) scroll = clampScroll(at - visibleRows() / 2);
	}

	public void onPreview(String name, SchematicPreview.Data data, String error, boolean busy) {
		if (name.equals(inflight)) inflight = null;
		if (busy) {
			// not an answer: leave it uncached and let tick() ask again once the gap has passed
			lastSent = System.currentTimeMillis();
			return;
		}
		if (data == null) {
			previews.put(name, new Preview(null, error.isEmpty() ? "could not load" : error));
			return;
		}
		previews.put(name, new Preview(SchematicPreviewModel.of(data), ""));
	}

	private void onSearchChanged(String value) {
		selected = null;
		filter = value.trim();
		refilter();
		scroll = 0;
	}

	private void refilter() {
		if (filter.isEmpty()) {
			shown = names;
		} else {
			String needle = filter.toLowerCase(Locale.ROOT);
			List<String> out = new ArrayList<>();
			for (String n : names) {
				if (n.toLowerCase(Locale.ROOT).contains(needle)) out.add(n);
			}
			shown = out;
		}
		scroll = clampScroll(scroll);
	}

	private int visibleRows() {
		return Math.max(1, listH / ROW_H);
	}

	private int clampScroll(int value) {
		return Math.max(0, Math.min(Math.max(0, shown.size() - visibleRows()), value));
	}

	private String target() {
		return selected != null ? selected : search.getValue().trim();
	}

	/**
	 * What the preview shows. Typed text only counts when nothing in the list matches it, or it is a
	 * listed name in full, so a partial filter does not ask the server for a file it cannot be.
	 */
	private String previewName() {
		if (selected != null) return selected;
		String typed = search.getValue().trim();
		if (typed.isEmpty()) return "";
		return shown.isEmpty() || shown.contains(typed) ? typed : "";
	}

	private String ellipsize(String text, int width) {
		if (this.font.width(text) <= width) return text;
		return this.font.plainSubstrByWidth(text, width - this.font.width("...")) + "...";
	}

	private boolean isStructureId(String name) {
		return name.indexOf(':') >= 0;
	}

	/** An empty name clears the plan, which only means something when there is one to clear. */
	private boolean canConfirm(String target) {
		return !target.isEmpty() || !parent.currentSchematic().isBlank();
	}

	private void confirm() {
		if (!canConfirm(target())) return;
		parent.chooseSchematic(target());
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public void tick() {
		super.tick();
		if (entity == null || entity.isRemoved() || !FPJobs.is(entity.getAIState().jobId(), FPJobs.BUILDER)) {
			Minecraft.getInstance().setScreen(parent);
			return;
		}
		long now = System.currentTimeMillis();
		String target = target();
		String previewTarget = previewName();
		if (!previewTarget.equals(wanted)) {
			wanted = previewTarget;
			wantedAt = selected != null ? now : now + TYPING_DEBOUNCE_MILLIS;
		}
		if (inflight != null && now - inflightSince > REQUEST_TIMEOUT_MILLIS) inflight = null;
		confirmButton.active = canConfirm(target);
		confirmButton.setMessage(Component.literal(target.isEmpty() && !parent.currentSchematic().isBlank() ? "Clear" : "Confirm"));
		if (previewTarget.isEmpty() || isStructureId(previewTarget) || now < wantedAt || inflight != null || previews.containsKey(previewTarget)) return;
		if (now - lastSent < MIN_REQUEST_GAP_MILLIS) return;
		inflight = previewTarget;
		inflightSince = now;
		lastSent = now;
		Network.getNetworkHandler().sendToServer(new RequestSchematicPreviewC2S(entity.getId(), previewTarget));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && mouseX >= listX && mouseX < listX + LIST_W
				&& mouseY >= listY && mouseY < listY + visibleRows() * ROW_H) {
			int row = scroll + (int) ((mouseY - listY) / ROW_H);
			if (row < shown.size()) {
				selected = shown.get(row);
				long now = System.currentTimeMillis();
				boolean again = row == lastClickRow && now - lastClickAt <= DOUBLE_CLICK_MILLIS;
				lastClickRow = row;
				lastClickAt = now;
				if (again) confirm();
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double delta) {
		if (delta != 0) {
			scroll = clampScroll(scroll - (int) Math.signum(delta) * 3);
			return true;
		}
		return super.mouseScrolled(x, y, delta);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
			// an empty name clears the plan, which takes a click on Clear rather than a stray Enter
			if (!target().isEmpty()) confirm();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		ctx.fill(0, 0, this.width, this.height, 0xA0050709);
		ctx.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, 0xFF000000);
		ctx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, COL_PANEL_BORDER);
		ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);
		ctx.fill(panelX, panelY, panelX + panelW, panelY + TITLE_H, COL_TITLE_BAR);
		ctx.fill(panelX + PADDING, panelY + TITLE_H - 1, panelX + panelW - PADDING, panelY + TITLE_H, COL_ACCENT);
		ctx.drawString(this.font, Component.literal("Choose schematic")
				.withStyle(s -> s.withColor(TextColor.fromRgb(COL_ACCENT & 0xFFFFFF)).withBold(true)),
				panelX + PADDING, panelY + 8, 0xFFFFFFFF, false);
		String count = listLoaded ? names.size() + " files" : "loading";
		ctx.drawString(this.font, muted(count), panelX + panelW - PADDING - this.font.width(count), panelY + 8, 0xFFFFFFFF, false);

		drawList(ctx, mouseX, mouseY);
		drawPreview(ctx);

		if (listTruncated) {
			ctx.drawString(this.font, muted("list capped at " + names.size()), listX, cancelButton.getY() + 4, 0xFFFFFFFF, false);
		}
		super.render(ctx, mouseX, mouseY, partialTick);
	}

	private void drawList(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(listX, listY, listX + LIST_W, listY + listH, COL_CELL);
		int rows = visibleRows();
		String current = target();
		String hoveredFull = null;
		for (int i = 0; i < rows; i++) {
			int index = scroll + i;
			if (index >= shown.size()) break;
			String name = shown.get(index);
			int y = listY + i * ROW_H;
			boolean over = mouseX >= listX && mouseX < listX + LIST_W && mouseY >= y && mouseY < y + ROW_H;
			if (name.equals(current)) ctx.fill(listX, y, listX + LIST_W, y + ROW_H, COL_CELL_PICKED);
			else if (over) ctx.fill(listX, y, listX + LIST_W, y + ROW_H, COL_CELL_HOVER);
			String label = ellipsize(name, LIST_W - 10);
			ctx.drawString(this.font, label, listX + 3, y + 2, COL_BODY, false);
			if (over && !label.equals(name)) hoveredFull = name;
		}
		if (hoveredFull != null) this.setTooltipForNextRenderPass(Component.literal(hoveredFull));
		if (shown.isEmpty()) {
			String note = !listLoaded ? "Loading..."
					: names.isEmpty() ? "No schematic files found" : "No match: Confirm uses the typed name";
			ctx.drawWordWrap(this.font, muted(note), listX + 4, listY + 4, LIST_W - 8, 0xFFFFFFFF);
		}
		if (shown.size() > rows) {
			int barH = Math.max(8, listH * rows / shown.size());
			int barY = listY + (listH - barH) * scroll / Math.max(1, shown.size() - rows);
			ctx.fill(listX + LIST_W - 3, barY, listX + LIST_W - 1, barY + barH, COL_MUTED);
		}
	}

	private void drawPreview(GuiGraphics ctx) {
		ctx.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0xFF0B1016);
		String name = previewName();
		int infoY = previewY + previewH + 4;
		if (name.isEmpty()) {
			centered(ctx, muted("Pick a schematic"), previewY + previewH / 2 - 4);
			return;
		}
		ctx.drawString(this.font, ellipsize(name, previewW), previewX, infoY, COL_ACCENT, false);
		if (isStructureId(name)) {
			centered(ctx, muted("No preview for structure ids"), previewY + previewH / 2 - 4);
			return;
		}
		Preview preview = previews.get(name);
		if (preview == null) {
			centered(ctx, muted("Loading..."), previewY + previewH / 2 - 4);
			return;
		}
		SchematicPreviewModel model = preview.model();
		if (model == null) {
			List<FormattedCharSequence> lines = this.font.split(
					Component.literal(preview.error()).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_RED & 0xFFFFFF))),
					previewW - 8);
			int y = previewY + Math.max(4, (previewH - lines.size() * 10) / 2);
			for (FormattedCharSequence line : lines) {
				ctx.drawString(this.font, line, previewX + 4, y, 0xFFFFFFFF, false);
				y += 10;
			}
			return;
		}
		int y = infoY + 10;
		y = drawWrapped(ctx, muted(model.sizeX() + " x " + model.sizeY() + " x " + model.sizeZ()
				+ ", " + model.total() + " blocks"), y);
		if (model.truncated()) {
			y = drawWrapped(ctx, Component.literal("Shows " + model.carried() + " of " + model.total() + " blocks")
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xE7C44F))), y);
		}
		if (model.simplified()) {
			drawWrapped(ctx, muted("Simplified preview"), y);
		}
		if (model.visible() == 0) {
			centered(ctx, muted("Nothing to show"), previewY + previewH / 2 - 4);
			return;
		}
		// drawn from scratch every frame here, so the spin is continuous rather than stepped
		float angle = (System.currentTimeMillis() - openedAt) * DEGREES_PER_SECOND / 1000f % 360f;
		SchematicPreviewRenderer.render(ctx, model, angle, previewX, previewY, previewX + previewW, previewY + previewH);
	}

	private int drawWrapped(GuiGraphics ctx, Component text, int y) {
		for (FormattedCharSequence line : this.font.split(text, previewW)) {
			ctx.drawString(this.font, line, previewX, y, 0xFFFFFFFF, false);
			y += 10;
		}
		return y;
	}

	private void centered(GuiGraphics ctx, Component text, int y) {
		ctx.drawString(this.font, text, previewX + (previewW - this.font.width(text)) / 2, y, 0xFFFFFFFF, false);
	}

	private static Component muted(String text) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_MUTED & 0xFFFFFF)));
	}
}
