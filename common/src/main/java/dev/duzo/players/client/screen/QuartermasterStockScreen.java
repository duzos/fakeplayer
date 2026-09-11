package dev.duzo.players.client.screen;

import commonnetwork.api.Network;
import dev.duzo.players.api.requests.ItemRequest;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.Job;
import dev.duzo.players.network.c2s.RequestItemPacketC2S;
import dev.duzo.players.network.c2s.RequestStockPacketC2S;
import dev.duzo.players.network.s2c.StockListPacketS2C;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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
	private static final int PANEL_H = 196;
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

	private final FakePlayerEntity entity;
	private final List<StockListPacketS2C.Entry> stock;
	private final int total;
	private int page;
	private float uiScale = 1f;
	private FlatButton prev;
	private FlatButton next;
	private FlatButton refresh;

	public QuartermasterStockScreen(FakePlayerEntity entity, List<StockListPacketS2C.Entry> stock, int total) {
		super(Component.literal("Storeroom"));
		this.entity = entity;
		this.stock = List.copyOf(stock);
		this.total = total;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	protected void init() {
		super.init();
		if (entity == null) {
			Minecraft.getInstance().gui.setScreen(null);
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
		Network.getNetworkHandler().sendToServer(new RequestStockPacketC2S(entity.getId()));
	}

	@Override
	public void tick() {
		super.tick();
		// the quartermaster can stop being one while this is open, at which point every click would
		// be silently swallowed by the server-side guards. Close instead of pretending to work.
		if (entity == null || entity.isRemoved() || entity.getAIState().job() != Job.QUARTERMASTER) {
			Minecraft.getInstance().gui.setScreen(null);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
		float scale = this.uiScale;
		int viewW = Math.round(this.width / scale);
		int viewH = Math.round(this.height / scale);
		int sMouseX = Math.round(mouseX / scale);
		int sMouseY = Math.round(mouseY / scale);

		ctx.pose().pushMatrix();
		ctx.pose().scale(scale, scale);

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
		ctx.text(this.font, title, x + PADDING, y + 8, 0xFFFFFFFF, false);

		String sub = stock.isEmpty() ? "empty"
				: stock.size() + (total > stock.size() ? " of " + total : "") + " kinds";
		ctx.text(this.font, Component.literal(sub)
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
			ctx.item(stack, cx + 1, cy + 1);
			ctx.itemDecorations(this.font, stack, cx + 1, cy + 1, shortCount(entry.count()));
			if (over) hovered = i;
		}

		if (stock.isEmpty()) {
			String none = "Nothing pooled. Mark some containers first.";
			ctx.text(this.font, Component.literal(none)
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_MUTED & 0xFFFFFF))),
					x + PADDING, gridY + 4, 0xFFFFFFFF, false);
		} else {
			String hint = "Click a stack, sneak-click for one, ctrl-click for all";
			ctx.text(this.font, Component.literal(hint)
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_MUTED & 0xFFFFFF))),
					x + PADDING, gridY + ROWS * CELL + 6, 0xFFFFFFFF, false);
			String pages = "page " + (page + 1) + "/" + (maxPage() + 1);
			ctx.text(this.font, Component.literal(pages)
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_BODY & 0xFFFFFF))),
					x + PANEL_W - PADDING - this.font.width(pages), gridY + ROWS * CELL + 6, 0xFFFFFFFF, false);
		}

		super.extractRenderState(ctx, sMouseX, sMouseY, partialTick);
		ctx.pose().popMatrix();

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
				ctx.setTooltipForNextFrame(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
			}
		}
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

	// Map real cursor coordinates into the scaled panel space so hit-testing lines up.
	private MouseButtonEvent scaled(MouseButtonEvent event) {
		return new MouseButtonEvent(event.x() / this.uiScale, event.y() / this.uiScale, event.buttonInfo());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent scaled = scaled(event);
		if (clickGrid(scaled)) return true;
		return super.mouseClicked(scaled, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(scaled(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return super.mouseDragged(scaled(event), dragX / this.uiScale, dragY / this.uiScale);
	}

	private boolean clickGrid(MouseButtonEvent event) {
		int viewW = Math.round(this.width / this.uiScale);
		int viewH = Math.round(this.height / this.uiScale);
		int gridX = (viewW - PANEL_W) / 2 + PADDING;
		int gridY = (viewH - PANEL_H) / 2 + TITLE_H + 8;
		int col = (int) ((event.x() - gridX) / CELL);
		int row = (int) ((event.y() - gridY) / CELL);
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
		// read off the click itself rather than the keyboard's current state, so the modifier that
		// was held when the player clicked is the one that counts
		if (event.hasControlDown()) {
			count = Math.min(ItemRequest.MAX_COUNT, entry.count());
		} else if (event.hasShiftDown()) {
			count = 1;
		} else {
			count = Math.min(stack.isEmpty() ? 1 : stack.getMaxStackSize(), entry.count());
		}
		Network.getNetworkHandler().sendToServer(
				new RequestItemPacketC2S(entity.getId(), entry.item().toString(), count));
		return true;
	}
}
