package dev.duzo.players.client.screen;

import commonnetwork.api.Network;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.Job;
import dev.duzo.players.network.c2s.CancelRequestPacketC2S;
import dev.duzo.players.network.c2s.RequestItemPacketC2S;
import dev.duzo.players.network.c2s.RequestStockPacketC2S;
import dev.duzo.players.network.s2c.StockListPacketS2C;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A read-only picker over a Quartermaster's pool. Clicking asks for the item; nothing here can move
 * one, because the only thing a click sends is a request packet.
 *
 * <p>Deliberately a plain Screen rather than an AbstractContainerScreen: the player is choosing an
 * item, not moving one, so there is no virtual inventory, no slot handling and no dupe surface.
 */
public class QuartermasterStockScreen extends Screen {
	private static final int PANEL_W = 200;
	private static final int PANEL_H = 238;
	private static final int PADDING = 12;
	private static final int TITLE_H = 24;
	private static final int CELL = 18;
	private static final int COLS = 9;
	private static final int ROWS = 6;
	private static final int PER_PAGE = COLS * ROWS;

	private static final int COL_PANEL = 0xFF11171F;
	private static final int COL_PANEL_BORDER = 0xFF2A3548;
	private static final int COL_TITLE_BAR = 0xFF161C24;
	private static final int COL_ACCENT = 0xFF2EC4FF;
	private static final int COL_BODY = 0xFFE6EAEF;
	private static final int COL_MUTED = 0xFF8E97A4;
	private static final int COL_CELL = 0xFF1B2430;
	private static final int COL_CELL_HOVER = 0xFF2C3D50;
	private static final int COL_GREEN = 0xFF54E08C;
	private static final int COL_YELLOW = 0xFFE7C44F;
	private static final int COL_RED = 0xFFE76060;

	private static final int PENDING_ROWS = 3;
	private static final int PENDING_H = 12;

	/** How often the open screen asks the server for a fresh snapshot. */
	private static final int REFRESH_TICKS = 20;

	private final FakePlayerEntity entity;
	private List<StockListPacketS2C.Entry> stock;
	private int total;
	private List<StockListPacketS2C.Pending> pending;
	private int sinceRefresh;
	private int page;
	private float uiScale = 1f;
	private FlatButton prev;
	private FlatButton next;
	private FlatButton refresh;

	public QuartermasterStockScreen(FakePlayerEntity entity, List<StockListPacketS2C.Entry> stock,
	                                int total, List<StockListPacketS2C.Pending> pending) {
		super(Component.literal("Storeroom"));
		this.entity = entity;
		this.stock = List.copyOf(stock);
		this.total = total;
		this.pending = List.copyOf(pending);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	protected void init() {
		super.init();
		if (entity == null) {
			Minecraft.getInstance().setScreen(null);
			return;
		}
		this.uiScale = Math.min(1.0F, Math.min((float) this.width / (PANEL_W + 16), (float) this.height / (PANEL_H + 16)));
		int viewW = Math.round(this.width / this.uiScale);
		int viewH = Math.round(this.height / this.uiScale);
		int left = (viewW - PANEL_W) / 2;
		int top = (viewH - PANEL_H) / 2;
		int bottom = top + PANEL_H - PADDING - 16;

		prev = new FlatButton(left + PADDING, bottom, 40, 16, Component.literal("Prev"), () -> turn(-1));
		this.addRenderableWidget(prev);
		next = new FlatButton(left + PADDING + 44, bottom, 40, 16, Component.literal("Next"), () -> turn(1));
		this.addRenderableWidget(next);
		refresh = new FlatButton(left + PANEL_W - PADDING - 56, bottom, 56, 16,
				Component.literal("Refresh"), this::refresh);
		this.addRenderableWidget(refresh);
		updateButtons();
	}

	private void turn(int delta) {
		page = Math.max(0, Math.min(maxPage(), page + delta));
		updateButtons();
	}

	private void updateButtons() {
		prev.active = page > 0;
		next.active = page < maxPage();
	}

	private int maxPage() {
		return Math.max(0, (stock.size() - 1) / PER_PAGE);
	}

	private void refresh() {
		sinceRefresh = 0;
		Network.getNetworkHandler().sendToServer(new RequestStockPacketC2S(entity.getId()));
	}

	public int entityId() {
		return entity.getId();
	}

	/**
	 * Takes a newer snapshot without reopening, so the outstanding list keeps up with the runners
	 * while the page the player was reading stays where it was.
	 */
	public void update(List<StockListPacketS2C.Entry> stock, int total,
	                   List<StockListPacketS2C.Pending> pending) {
		this.stock = List.copyOf(stock);
		this.total = total;
		this.pending = List.copyOf(pending);
		this.page = Math.min(this.page, maxPage());
		updateButtons();
	}

	@Override
	public void tick() {
		super.tick();
		// the quartermaster can stop being one while this is open, at which point every click would
		// be silently swallowed by the server-side guards. Close instead of pretending to work.
		if (entity == null || entity.isRemoved() || entity.getAIState().job() != Job.QUARTERMASTER) {
			Minecraft.getInstance().setScreen(null);
			return;
		}
		if (++sinceRefresh >= REFRESH_TICKS) refresh();
	}

	@Override
	public void render(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		float scale = this.uiScale;
		int viewW = Math.round(this.width / scale);
		int viewH = Math.round(this.height / scale);
		int sMouseX = Math.round(mouseX / scale);
		int sMouseY = Math.round(mouseY / scale);

		ctx.pose().pushPose();
		ctx.pose().scale(scale, scale, 1.0F);

		ctx.fill(0, 0, viewW, viewH, 0xA0050709);
		int x = (viewW - PANEL_W) / 2;
		int y = (viewH - PANEL_H) / 2;

		ctx.fill(x - 2, y - 2, x + PANEL_W + 2, y + PANEL_H + 2, 0xFF000000);
		ctx.fill(x - 1, y - 1, x + PANEL_W + 1, y + PANEL_H + 1, COL_PANEL_BORDER);
		ctx.fill(x, y, x + PANEL_W, y + PANEL_H, COL_PANEL);
		ctx.fill(x, y, x + PANEL_W, y + TITLE_H, COL_TITLE_BAR);
		ctx.fill(x + PADDING, y + TITLE_H - 1, x + PANEL_W - PADDING, y + TITLE_H, COL_ACCENT);

		MutableComponent title = Component.literal("Storeroom")
				.withStyle(s -> s.withColor(TextColor.fromRgb(COL_ACCENT & 0xFFFFFF)).withBold(true));
		ctx.drawString(this.font, title, x + PADDING, y + 8, 0xFFFFFFFF, false);

		String sub = stock.isEmpty() ? "empty"
				: stock.size() + (total > stock.size() ? " of " + total : "") + " kinds";
		ctx.drawString(this.font, Component.literal(sub)
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_MUTED & 0xFFFFFF))),
				x + PANEL_W - PADDING - this.font.width(sub), y + 8, 0xFFFFFFFF, false);

		int gridX = x + PADDING;
		int gridY = y + TITLE_H + 8;
		int hovered = -1;
		List<StockListPacketS2C.Entry> shown = pageEntries();
		for (int i = 0; i < PER_PAGE; i++) {
			int cx = gridX + (i % COLS) * CELL;
			int cy = gridY + (i / COLS) * CELL;
			boolean over = sMouseX >= cx && sMouseX < cx + CELL - 1 && sMouseY >= cy && sMouseY < cy + CELL - 1;
			ctx.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, over && i < shown.size() ? COL_CELL_HOVER : COL_CELL);
			if (i >= shown.size()) continue;
			StockListPacketS2C.Entry entry = shown.get(i);
			ItemStack stack = stackOf(entry);
			if (stack.isEmpty()) continue;
			ctx.renderItem(stack, cx + 1, cy + 1);
			drawCount(ctx, cx, cy, entry.count());
			if (over) hovered = i;
		}

		if (stock.isEmpty()) {
			String none = "Nothing pooled. Mark some containers first.";
			ctx.drawString(this.font, Component.literal(none)
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_MUTED & 0xFFFFFF))),
					x + PADDING, gridY + 4, 0xFFFFFFFF, false);
		} else {
			String hint = "Click: stack, shift: one, ctrl: all";
			ctx.drawString(this.font, Component.literal(hint)
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_MUTED & 0xFFFFFF))),
					x + PADDING, gridY + ROWS * CELL + 6, 0xFFFFFFFF, false);
		}

		drawPending(ctx, x, pendingTop(y), sMouseX, sMouseY);

		if (!stock.isEmpty()) {
			String pages = (page + 1) + "/" + (maxPage() + 1);
			ctx.drawString(this.font, Component.literal(pages)
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_BODY & 0xFFFFFF))),
					x + PADDING + 92, y + PANEL_H - PADDING - 16 + 4, 0xFFFFFFFF, false);
		}

		super.render(ctx, sMouseX, sMouseY, partialTick);
		ctx.pose().popPose();

		// outside the scaled matrix on purpose, or the tooltip renders at panel scale in the wrong place
		if (hovered >= 0) {
			StockListPacketS2C.Entry entry = pageEntries().get(hovered);
			ItemStack stack = stackOf(entry);
			if (!stack.isEmpty()) {
				List<Component> lines = new ArrayList<>();
				lines.add(stack.getHoverName());
				lines.add(Component.literal("pooled: " + entry.count()).withStyle(ChatFormatting.GRAY));
				lines.add(Component.literal("click for " + Math.min(stack.getMaxStackSize(), entry.count()))
						.withStyle(ChatFormatting.DARK_GRAY));
				ctx.renderTooltip(this.font, lines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
			}
		}
	}

	private int pendingTop(int panelY) {
		return panelY + TITLE_H + 8 + ROWS * CELL + 20;
	}

	/**
	 * Outstanding requests, so a mis-click is visible and undoable. Without this a request that
	 * shortfalls sits on the board until the player logs out, and a grid makes those cheap to make.
	 */
	private void drawPending(GuiGraphics ctx, int panelX, int top, int sMouseX, int sMouseY) {
		ctx.drawString(this.font, Component.literal("Outstanding")
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_MUTED & 0xFFFFFF))),
				panelX + PADDING, top, 0xFFFFFFFF, false);
		if (pending.isEmpty()) {
			ctx.drawString(this.font, Component.literal("nothing waiting")
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_MUTED & 0xFFFFFF))),
					panelX + PADDING + 70, top, 0xFFFFFFFF, false);
			return;
		}
		int shown = Math.min(PENDING_ROWS, pending.size());
		for (int i = 0; i < shown; i++) {
			StockListPacketS2C.Pending row = pending.get(i);
			int rowY = top + 12 + i * PENDING_H;
			int dot = row.waiting() ? COL_YELLOW : COL_GREEN;
			ctx.fill(panelX + PADDING, rowY + 2, panelX + PADDING + 4, rowY + 6, dot);
			String label = shortName(row.item()) + " x" + row.remaining();
			ctx.drawString(this.font, Component.literal(label)
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_BODY & 0xFFFFFF))),
					panelX + PADDING + 8, rowY, 0xFFFFFFFF, false);
			if (!row.mine()) continue;
			int cancelX = cancelX(panelX);
			boolean over = sMouseX >= cancelX && sMouseX < cancelX + 8 && sMouseY >= rowY && sMouseY < rowY + 9;
			ctx.drawString(this.font, Component.literal("x")
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb((over ? COL_RED : COL_MUTED) & 0xFFFFFF))),
					cancelX, rowY, 0xFFFFFFFF, false);
		}
		if (pending.size() > shown) {
			String more = "+" + (pending.size() - shown) + " more";
			ctx.drawString(this.font, Component.literal(more)
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_MUTED & 0xFFFFFF))),
					panelX + PADDING + 8, top + 12 + shown * PENDING_H, 0xFFFFFFFF, false);
		}
	}

	private int cancelX(int panelX) {
		return panelX + PANEL_W - PADDING - 8;
	}

	private static String shortName(net.minecraft.resources.ResourceLocation id) {
		String path = id.getPath();
		return path.length() > 22 ? path.substring(0, 21) + "…" : path;
	}

	private List<StockListPacketS2C.Entry> pageEntries() {
		int from = Math.min(page * PER_PAGE, stock.size());
		int to = Math.min(from + PER_PAGE, stock.size());
		return stock.subList(from, to);
	}

	private ItemStack stackOf(StockListPacketS2C.Entry entry) {
		// an id this client does not know (server-only content) simply has no icon to draw
		return BuiltInRegistries.ITEM.getOptional(entry.item()).map(ItemStack::new).orElse(ItemStack.EMPTY);
	}

	private static String shortCount(int count) {
		if (count < 1000) return String.valueOf(count);
		if (count < 100000) return (count / 1000) + "k";
		return "lots";
	}

	/**
	 * Stack counts, drawn small. The vanilla decoration is sized for a 16px slot holding at most
	 * two digits, and a pooled count of several hundred simply runs into the next cell.
	 */
	private void drawCount(GuiGraphics ctx, int cx, int cy, int count) {
		if (count <= 1) return;
		String text = shortCount(count);
		float scale = 0.6F;
		int w = Math.round(this.font.width(text) * scale);
		int tx = cx + CELL - 2 - w;
		int ty = cy + CELL - 2 - Math.round(this.font.lineHeight * scale);
		ctx.pose().pushPose();
		// the item is drawn at z 150, so a count at z 0 ends up behind it. vanilla's own
		// decoration uses 200 for the same reason
		ctx.pose().translate(tx, ty, 200.0F);
		ctx.pose().scale(scale, scale, 1.0F);
		ctx.drawString(this.font, text, 1, 1, 0xFF000000, false);
		ctx.drawString(this.font, text, 0, 0, 0xFFFFFFFF, false);
		ctx.pose().popPose();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		double sx = mouseX / this.uiScale;
		double sy = mouseY / this.uiScale;
		if (clickCancel(sx, sy)) return true;
		if (clickGrid(sx, sy)) return true;
		return super.mouseClicked(sx, sy, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return super.mouseReleased(mouseX / this.uiScale, mouseY / this.uiScale, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		return super.mouseDragged(mouseX / this.uiScale, mouseY / this.uiScale, button,
				dragX / this.uiScale, dragY / this.uiScale);
	}

	private boolean clickCancel(double mouseX, double mouseY) {
		if (pending.isEmpty()) return false;
		int viewH = Math.round(this.height / this.uiScale);
		int viewW = Math.round(this.width / this.uiScale);
		int panelX = (viewW - PANEL_W) / 2;
		int top = pendingTop((viewH - PANEL_H) / 2);
		int cancelX = cancelX(panelX);
		if (mouseX < cancelX || mouseX >= cancelX + 8) return false;

		int shown = Math.min(PENDING_ROWS, pending.size());
		for (int i = 0; i < shown; i++) {
			int rowY = top + 12 + i * PENDING_H;
			if (mouseY < rowY || mouseY >= rowY + 9) continue;
			StockListPacketS2C.Pending row = pending.get(i);
			if (!row.mine()) return true;
			Network.getNetworkHandler().sendToServer(
					new CancelRequestPacketC2S(entity.getId(), row.item().toString()));
			refresh();
			return true;
		}
		return false;
	}

	private boolean clickGrid(double mouseX, double mouseY) {
		int viewW = Math.round(this.width / this.uiScale);
		int viewH = Math.round(this.height / this.uiScale);
		int gridX = (viewW - PANEL_W) / 2 + PADDING;
		int gridY = (viewH - PANEL_H) / 2 + TITLE_H + 8;
		int col = (int) ((mouseX - gridX) / CELL);
		int row = (int) ((mouseY - gridY) / CELL);
		if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return false;

		List<StockListPacketS2C.Entry> shown = pageEntries();
		int index = row * COLS + col;
		if (index >= shown.size()) return false;

		StockListPacketS2C.Entry entry = shown.get(index);
		// refused client-side: the pool is empty of this now, and letting it through would put a
		// request on the board that shortfalls and then lives there until the player logs out
		if (entry.count() <= 0) return true;

		ItemStack stack = stackOf(entry);
		int count;
		if (hasControlDown()) {
			count = Math.min(ItemRequest.MAX_COUNT, entry.count());
		} else if (hasShiftDown()) {
			count = 1;
		} else {
			count = Math.min(stack.isEmpty() ? 1 : stack.getMaxStackSize(), entry.count());
		}
		Network.getNetworkHandler().sendToServer(
				new RequestItemPacketC2S(entity.getId(), entry.item().toString(), count));
		return true;
	}
}
