package dev.duzo.players.client.screen;

import commonnetwork.api.Network;
import dev.duzo.players.core.AIMarkerItem;
import dev.duzo.players.entities.FakePlayerEntity;
import dev.duzo.players.entities.ai.AIState;
import dev.duzo.players.entities.ai.GuardJobExecutor;
import dev.duzo.players.entities.ai.Job;
import dev.duzo.players.network.c2s.BondPacketC2S;
import dev.duzo.players.network.c2s.ClearPatrolPacketC2S;
import dev.duzo.players.network.c2s.GiveAIMarkerPacketC2S;
import dev.duzo.players.network.c2s.OpenCrafterLearnPacketC2S;
import dev.duzo.players.network.c2s.SetAIFilterPacketC2S;
import dev.duzo.players.network.c2s.SetJobPacketC2S;
import dev.duzo.players.network.c2s.StartStopJobPacketC2S;
import dev.duzo.players.network.c2s.ToggleFakePlayerFlagPacketC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.List;
import java.util.UUID;

public class AISubMenuScreen extends Screen {
	private static final int PANEL_W = 240;
	private static final int PANEL_H = 298;
	private static final int PADDING = 14;
	private static final int TITLE_H = 26;
	private static final int ROW_H = 18;
	private static final int BTN_H = 16;
	private static final int RIGHT_BTN_W = 64;

	private static final int COL_PANEL = 0xFF11171F;
	private static final int COL_PANEL_BORDER = 0xFF2A3548;
	private static final int COL_PANEL_TOP = 0xFF1F262E;
	private static final int COL_TITLE_BAR = 0xFF161C24;
	private static final int COL_ACCENT = 0xFF2EC4FF;
	private static final int COL_DIVIDER = 0xFF222932;
	private static final int COL_LABEL = 0xFF6B7787;
	private static final int COL_BODY = 0xFFE6EAEF;
	private static final int COL_MUTED = 0xFF8E97A4;
	private static final int COL_GREEN = 0xFF54E08C;
	private static final int COL_YELLOW = 0xFFE7C44F;
	private static final int COL_RED = 0xFFE76060;
	private static final int COL_AQUA = 0xFF55EAFF;

	private static final int RUN_BG = 0xFF1B3A28;
	private static final int RUN_BG_HOVER = 0xFF265A3A;
	private static final int RUN_BORDER = 0xFF2F5A40;
	private static final int RUN_BORDER_HOVER = 0xFF54E08C;
	private static final int STOP_BG = 0xFF3A1E1E;
	private static final int STOP_BG_HOVER = 0xFF5A2A2A;
	private static final int STOP_BORDER = 0xFF5A2F2F;
	private static final int STOP_BORDER_HOVER = 0xFFE76060;

	private final FakePlayerEntity entity;

	private FlatButton bondButton;
	private FlatButton noAiButton;
	private FlatButton jobButton;
	private FlatButton waypointButton;
	private FlatButton regionButton;
	private FlatButton depositButton;
	private FlatButton sourceButton;
	private FlatButton teachButton;
	private FlatButton patrolClearButton;
	private EditBox filterEdit;
	private FlatButton filterButton;
	private FlatButton startStopButton;

	private int ownerSectionY;
	private int behaviourSectionY;
	private int markerSectionY;
	private int rightBtnX;
	private int innerLeft;

	// Which marker rows a job actually uses, in display order. Drives both layout and rendering.
	private enum Row { WAYPOINT, REGION, DEPOSIT, SOURCE, TEACH, FILTER, PATROL }

	// Shrinks the whole panel when the screen is too small to fit it (large GUI scale).
	private float uiScale = 1f;

	public AISubMenuScreen(FakePlayerEntity entity) {
		super(Component.literal("AI"));
		this.entity = entity;
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
		int panelLeft = (viewW - PANEL_W) / 2;
		int panelTop = (viewH - PANEL_H) / 2;
		int innerLeft = panelLeft + PADDING;
		int innerRight = panelLeft + PANEL_W - PADDING;
		int innerWidth = innerRight - innerLeft;
		int rightBtnX = innerRight - RIGHT_BTN_W;

		FlatButton close = new FlatButton(innerRight - 14, panelTop + 6, 14, 14,
				Component.literal("x"),
				() -> Minecraft.getInstance().setScreen(null))
				.noBackground()
				.noBorder()
				.withText(0xFF8E97A4, 0xFFE76060);
		this.addRenderableWidget(close);

		int y = panelTop + TITLE_H + 8;

		ownerSectionY = y;
		y += 14;
		bondButton = new FlatButton(rightBtnX, y, RIGHT_BTN_W, BTN_H, Component.literal("Bond"), this::toggleBond);
		this.addRenderableWidget(bondButton);
		y += BTN_H + 8;

		behaviourSectionY = y;
		y += 14;
		noAiButton = new FlatButton(rightBtnX, y, RIGHT_BTN_W, BTN_H, Component.literal("No AI"), this::toggleNoAi);
		this.addRenderableWidget(noAiButton);
		y += ROW_H;
		jobButton = new FlatButton(rightBtnX, y, RIGHT_BTN_W, BTN_H, Component.literal("Cycle"), this::cycleJob);
		this.addRenderableWidget(jobButton);
		y += BTN_H + 8;

		markerSectionY = y;
		this.rightBtnX = rightBtnX;
		this.innerLeft = innerLeft;

		// All marker widgets are created once; relayout() positions and shows only the ones the current job uses.
		waypointButton = new FlatButton(rightBtnX, markerSectionY, RIGHT_BTN_W, BTN_H,
				Component.literal("Mark"), () -> giveMarker(AIMarkerItem.PURPOSE_WAYPOINT));
		this.addRenderableWidget(waypointButton);
		regionButton = new FlatButton(rightBtnX, markerSectionY, RIGHT_BTN_W, BTN_H,
				Component.literal("Mark"), () -> giveMarker(AIMarkerItem.PURPOSE_REGION));
		this.addRenderableWidget(regionButton);
		depositButton = new FlatButton(rightBtnX, markerSectionY, RIGHT_BTN_W, BTN_H,
				Component.literal("Mark"), () -> giveMarker(AIMarkerItem.PURPOSE_CHEST_PICKER, AIMarkerItem.CHEST_SLOT_DEPOSIT));
		this.addRenderableWidget(depositButton);
		sourceButton = new FlatButton(rightBtnX, markerSectionY, RIGHT_BTN_W, BTN_H,
				Component.literal("Mark"), () -> giveMarker(AIMarkerItem.PURPOSE_CHEST_PICKER, AIMarkerItem.CHEST_SLOT_SOURCE));
		this.addRenderableWidget(sourceButton);
		teachButton = new FlatButton(rightBtnX, markerSectionY, RIGHT_BTN_W, BTN_H,
				Component.literal("Teach"), this::openTeach);
		this.addRenderableWidget(teachButton);
		patrolClearButton = new FlatButton(rightBtnX, markerSectionY, RIGHT_BTN_W, BTN_H,
				Component.literal("Clear"), this::clearPatrol);
		this.addRenderableWidget(patrolClearButton);
		filterEdit = new EditBox(this.font, innerLeft + 52, markerSectionY, 88, BTN_H, Component.literal("filter"));
		filterEdit.setMaxLength(512);
		filterEdit.setValue(filterText(entity.getAIState()));
		this.addRenderableWidget(filterEdit);
		filterButton = new FlatButton(rightBtnX, markerSectionY, RIGHT_BTN_W, BTN_H, Component.literal("Apply"), this::applyFilter);
		this.addRenderableWidget(filterButton);

		int startStopY = markerSectionY + 18 + 4 * ROW_H + 48;
		startStopButton = new FlatButton(innerLeft, startStopY, innerWidth, 22, startStopLabel(), this::toggleRun).bold();
		this.addRenderableWidget(startStopButton);

		refreshLabels();
	}

	@Override
	public void tick() {
		super.tick();
		refreshLabels();
	}

	private void refreshLabels() {
		AIState s = entity.getAIState();
		if (bondButton != null) bondButton.setMessage(bondButtonLabel(s));
		relayout(s);
		if (patrolClearButton != null && patrolClearButton.visible) {
			patrolClearButton.active = GuardJobExecutor.readPatrolPoints(s).length > 0;
		}
		if (startStopButton != null) {
			startStopButton.setMessage(startStopLabel());
			boolean run = s.running();
			startStopButton.setColors(
					run ? STOP_BG : RUN_BG,
					run ? STOP_BG_HOVER : RUN_BG_HOVER,
					run ? STOP_BORDER : RUN_BORDER,
					run ? STOP_BORDER_HOVER : RUN_BORDER_HOVER,
					run ? 0xFFFF8A8A : 0xFF8AFFB0,
					run ? 0xFFFFC0C0 : 0xFFC0FFD0);
		}
	}

	private List<Row> rowsFor(Job job) {
		return switch (job) {
			case IDLE -> List.of(Row.WAYPOINT);
			case GUARD -> List.of(Row.WAYPOINT, Row.PATROL);
			case COURIER -> List.of(Row.SOURCE, Row.DEPOSIT);
			case MINER -> List.of(Row.REGION, Row.DEPOSIT, Row.FILTER);
			case LUMBERJACK -> List.of(Row.REGION, Row.DEPOSIT);
			case FISHERMAN -> List.of(Row.WAYPOINT, Row.DEPOSIT);
			case FARMER -> List.of(Row.REGION, Row.DEPOSIT);
			case CRAFTER -> List.of(Row.WAYPOINT, Row.SOURCE, Row.DEPOSIT, Row.TEACH);
			default -> List.of();
		};
	}

	private void relayout(AIState s) {
		waypointButton.visible = false;
		regionButton.visible = false;
		depositButton.visible = false;
		sourceButton.visible = false;
		teachButton.visible = false;
		patrolClearButton.visible = false;
		filterButton.visible = false;
		filterEdit.visible = false;
		List<Row> rows = rowsFor(s.job());
		for (int i = 0; i < rows.size(); i++) {
			int btnY = markerSectionY + 18 + i * ROW_H - 4;
			switch (rows.get(i)) {
				case WAYPOINT -> place(waypointButton, btnY);
				case REGION -> place(regionButton, btnY);
				case DEPOSIT -> place(depositButton, btnY);
				case SOURCE -> place(sourceButton, btnY);
				case TEACH -> place(teachButton, btnY);
				case PATROL -> place(patrolClearButton, btnY);
				case FILTER -> {
					place(filterButton, btnY);
					filterEdit.setX(innerLeft + 52);
					filterEdit.setY(btnY);
					filterEdit.visible = true;
				}
			}
		}
	}

	private void place(FlatButton b, int y) {
		b.setX(rightBtnX);
		b.setY(y);
		b.visible = true;
	}

	private Component bondButtonLabel(AIState s) {
		UUID self = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
		boolean ours = s.hasOwner() && s.ownerUUID().equals(self);
		return Component.literal(ours ? "Unbond" : "Bond");
	}

	private Component startStopLabel() {
		boolean run = entity.getAIState().running();
		return Component.literal(run ? "STOP JOB" : "START JOB");
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
		ctx.fill(x, y, x + PANEL_W, y + 1, COL_PANEL_TOP);
		ctx.fill(x, y, x + PANEL_W, y + TITLE_H, COL_TITLE_BAR);
		ctx.fill(x + PADDING, y + TITLE_H - 1, x + PANEL_W - PADDING, y + TITLE_H, COL_ACCENT);

		drawTitle(ctx, x, y);

		AIState s = entity.getAIState();

		drawSectionHeader(ctx, x, ownerSectionY, "OWNER");
		drawOwnerRow(ctx, x, ownerSectionY + 18, s);

		drawSectionHeader(ctx, x, behaviourSectionY, "BEHAVIOUR");
		drawAiRow(ctx, x, behaviourSectionY + 18);
		drawJobRow(ctx, x, behaviourSectionY + 18 + ROW_H, s);

		List<Row> rows = rowsFor(s.job());
		if (!rows.isEmpty()) drawSectionHeader(ctx, x, markerSectionY, "MARKERS");
		for (int i = 0; i < rows.size(); i++) {
			int rowY = markerSectionY + 18 + i * ROW_H;
			switch (rows.get(i)) {
				case WAYPOINT -> drawWaypointRow(ctx, x, rowY, s);
				case REGION -> drawRegionRow(ctx, x, rowY, s);
				case DEPOSIT -> drawMarkerRow(ctx, x, rowY, "Deposit", s.depositChest());
				case SOURCE -> drawMarkerRow(ctx, x, rowY, "Source", s.sourceChest());
				case TEACH -> {
					CompoundTag recipe = s.jobParams().getCompoundOrEmpty("Recipe");
					boolean learned = !recipe.isEmpty();
					drawChip(ctx, x + PADDING, rowY,
							learned ? COL_GREEN : COL_MUTED,
							learned ? "Recipe  learned" : "Recipe  teach one",
							learned ? COL_BODY : COL_MUTED);
				}
				case FILTER -> drawChip(ctx, x + PADDING, rowY, COL_AQUA, "Filter", COL_BODY);
				case PATROL -> drawPatrolRow(ctx, x, rowY, s);
			}
		}

		super.render(ctx, sMouseX, sMouseY, partialTick);
		ctx.pose().popPose();
	}

	// Map real cursor coordinates into the scaled panel space so widget hit-testing lines up.
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return super.mouseClicked(mouseX / this.uiScale, mouseY / this.uiScale, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return super.mouseReleased(mouseX / this.uiScale, mouseY / this.uiScale, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		return super.mouseDragged(mouseX / this.uiScale, mouseY / this.uiScale, button, dragX / this.uiScale, dragY / this.uiScale);
	}

	private void drawTitle(GuiGraphics ctx, int x, int y) {
		MutableComponent title = Component.literal("AI").withStyle(s -> s.withColor(TextColor.fromRgb(COL_AQUA & 0xFFFFFF)).withBold(true));
		String name = entity.getSkinData().name();
		title.append(Component.literal("  ").append(Component.literal("-  " + name).withStyle(s -> s.withColor(TextColor.fromRgb(COL_LABEL & 0xFFFFFF)))));
		ctx.drawString(this.font, title, x + PADDING, y + 9, 0xFFFFFFFF, false);
	}

	private void drawSectionHeader(GuiGraphics ctx, int panelX, int y, String label) {
		ctx.drawString(this.font, Component.literal(label).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COL_LABEL & 0xFFFFFF))),
				panelX + PADDING, y, 0xFFFFFFFF, false);
		int textW = this.font.width(label);
		int lineLeft = panelX + PADDING + textW + 6;
		int lineRight = panelX + PANEL_W - PADDING;
		ctx.fill(lineLeft, y + 3, lineRight, y + 4, COL_DIVIDER);
	}

	private void drawOwnerRow(GuiGraphics ctx, int panelX, int y, AIState s) {
		UUID self = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
		boolean ours = s.hasOwner() && s.ownerUUID().equals(self);
		int dot;
		String text;
		int textColor;
		if (ours) {
			dot = COL_GREEN; text = "bonded to you"; textColor = COL_BODY;
		} else if (s.hasOwner()) {
			dot = COL_RED; text = "owned by another"; textColor = COL_MUTED;
		} else {
			dot = COL_MUTED; text = "no owner"; textColor = COL_BODY;
		}
		drawChip(ctx, panelX + PADDING, y, dot, text, textColor);
	}

	private void drawAiRow(GuiGraphics ctx, int panelX, int y) {
		boolean on = !entity.isNoAi();
		int dot = on ? COL_GREEN : COL_RED;
		String text = on ? "AI active" : "AI disabled";
		drawChip(ctx, panelX + PADDING, y, dot, text, COL_BODY);
	}

	private void drawJobRow(GuiGraphics ctx, int panelX, int y, AIState s) {
		drawChip(ctx, panelX + PADDING, y, COL_AQUA, "Job: " + s.job().label(), COL_BODY);
	}

	private void drawMarkerRow(GuiGraphics ctx, int panelX, int y, String name, BlockPos pos) {
		int dot = pos == null ? COL_MUTED : COL_GREEN;
		String text;
		int textColor;
		if (pos == null) {
			text = name + "  unset";
			textColor = COL_MUTED;
		} else {
			text = name + "  " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
			textColor = COL_BODY;
		}
		drawChip(ctx, panelX + PADDING, y, dot, text, textColor);
	}

	private void drawWaypointRow(GuiGraphics ctx, int panelX, int y, AIState s) {
		if (s.job() == Job.GUARD) {
			int count = GuardJobExecutor.readPatrolPoints(s).length;
			int dot = count >= 2 ? COL_GREEN : count == 1 ? COL_YELLOW : COL_MUTED;
			String text = count == 0 ? "Waypoint  add patrol" : "Waypoint  +1 (" + count + " pts)";
			drawChip(ctx, panelX + PADDING, y, dot, text, count == 0 ? COL_MUTED : COL_BODY);
		} else {
			drawMarkerRow(ctx, panelX, y, crafterActive() ? "Table" : "Waypoint", s.waypoint());
		}
	}

	private void drawPatrolRow(GuiGraphics ctx, int panelX, int y, AIState s) {
		if (s.job() != Job.GUARD) return;
		long[] points = GuardJobExecutor.readPatrolPoints(s);
		int count = points.length;
		int radius = GuardJobExecutor.readRadius(s);
		int dot = count >= 2 ? COL_GREEN : count == 1 ? COL_YELLOW : COL_RED;
		String text = "Patrol    " + count + " pts, r=" + radius;
		drawChip(ctx, panelX + PADDING, y, dot, text, count == 0 ? COL_MUTED : COL_BODY);
		int shown = Math.min(count, 4);
		for (int i = 0; i < shown; i++) {
			BlockPos p = BlockPos.of(points[i]);
			String point = (i + 1) + ". " + p.getX() + ", " + p.getY() + ", " + p.getZ();
			drawChip(ctx, panelX + PADDING + 10, y + 11 + i * 10, COL_AQUA, point, COL_MUTED);
		}
		if (count > shown) {
			drawChip(ctx, panelX + PADDING + 10, y + 11 + shown * 10, COL_AQUA, "+" + (count - shown) + " more", COL_MUTED);
		}
	}

	private void drawRegionRow(GuiGraphics ctx, int panelX, int y, AIState s) {
		int dot;
		String text;
		int textColor;
		if (s.regionA() == null && s.regionB() == null) {
			dot = COL_MUTED; text = "Region    unset"; textColor = COL_MUTED;
		} else if (s.regionA() != null && s.regionB() == null) {
			dot = COL_YELLOW; text = "Region    A pending"; textColor = COL_BODY;
		} else {
			dot = COL_GREEN; text = "Region    set"; textColor = COL_BODY;
		}
		drawChip(ctx, panelX + PADDING, y, dot, text, textColor);
	}

	private void drawChip(GuiGraphics ctx, int x, int y, int dotColor, String text, int textColor) {
		ctx.fill(x, y + 3, x + 4, y + 7, dotColor);
		ctx.drawString(this.font,
				Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(textColor & 0xFFFFFF))),
				x + 10, y, 0xFFFFFFFF, false);
	}

	private void toggleBond() {
		AIState s = entity.getAIState();
		UUID self = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
		boolean ours = s.hasOwner() && s.ownerUUID().equals(self);
		Network.getNetworkHandler().sendToServer(new BondPacketC2S(entity.getId(), !ours));
	}

	private void toggleNoAi() {
		boolean newNoAi = !entity.isNoAi();
		Network.getNetworkHandler().sendToServer(new ToggleFakePlayerFlagPacketC2S(entity.getId(), ToggleFakePlayerFlagPacketC2S.FLAG_NO_AI, newNoAi));
	}

	private void cycleJob() {
		Job[] all = Job.values();
		int current = entity.getAIState().job().ordinal();
		Job next = Job.NONE;
		for (int i = 1; i <= all.length; i++) {
			Job candidate = all[(current + i) % all.length];
			if (candidate != Job.PATROL && candidate != Job.DEPOSIT) {
				next = candidate;
				break;
			}
		}
		Network.getNetworkHandler().sendToServer(new SetJobPacketC2S(entity.getId(), next.ordinal()));
	}

	private void giveMarker(byte mode) {
		giveMarker(mode, AIMarkerItem.CHEST_SLOT_DEPOSIT);
	}

	private void giveMarker(byte mode, byte slot) {
		Network.getNetworkHandler().sendToServer(new GiveAIMarkerPacketC2S(entity.getId(), mode, slot));
		Minecraft.getInstance().setScreen(null);
	}

	private boolean crafterActive() {
		return entity.getAIState().job() == Job.CRAFTER;
	}

	private void openTeach() {
		Network.getNetworkHandler().sendToServer(new OpenCrafterLearnPacketC2S(entity.getId()));
		Minecraft.getInstance().setScreen(null);
	}

	private void applyFilter() {
		Network.getNetworkHandler().sendToServer(new SetAIFilterPacketC2S(entity.getId(), filterEdit.getValue()));
	}

	private String filterText(AIState state) {
		String tag = state.filter().contains("Tag") ? state.filter().getString("Tag") : "c:ores";
		return tag.isEmpty() ? "c:ores" : tag;
	}

	private void toggleRun() {
		Network.getNetworkHandler().sendToServer(new StartStopJobPacketC2S(entity.getId(), !entity.getAIState().running()));
	}

	private void clearPatrol() {
		Network.getNetworkHandler().sendToServer(new ClearPatrolPacketC2S(entity.getId()));
	}
}
