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
import dev.duzo.players.network.c2s.SetJobPacketC2S;
import dev.duzo.players.network.c2s.StartStopJobPacketC2S;
import dev.duzo.players.network.c2s.ToggleFakePlayerFlagPacketC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

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
	private FlatButton patrolClearButton;
	private FlatButton startStopButton;
	private int patrolRowY;

	private int ownerSectionY;
	private int behaviourSectionY;
	private int markerSectionY;

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

		int panelLeft = (this.width - PANEL_W) / 2;
		int panelTop = (this.height - PANEL_H) / 2;
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
		y += 14;
		waypointButton = new FlatButton(rightBtnX, y, RIGHT_BTN_W, BTN_H,
				Component.literal("Mark"), () -> giveMarker(AIMarkerItem.PURPOSE_WAYPOINT));
		this.addRenderableWidget(waypointButton);
		y += ROW_H;
		regionButton = new FlatButton(rightBtnX, y, RIGHT_BTN_W, BTN_H,
				Component.literal("Mark"), () -> giveMarker(AIMarkerItem.PURPOSE_REGION));
		this.addRenderableWidget(regionButton);
		y += ROW_H;
		depositButton = new FlatButton(rightBtnX, y, RIGHT_BTN_W, BTN_H,
				Component.literal("Mark"), () -> giveMarker(AIMarkerItem.PURPOSE_CHEST_PICKER));
		this.addRenderableWidget(depositButton);
		y += ROW_H;
		patrolRowY = y;
		patrolClearButton = new FlatButton(rightBtnX, y, RIGHT_BTN_W, BTN_H,
				Component.literal("Clear"), this::clearPatrol);
		this.addRenderableWidget(patrolClearButton);
		y += BTN_H + 54;

		startStopButton = new FlatButton(innerLeft, y, innerWidth, 22, startStopLabel(), this::toggleRun).bold();
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
		if (patrolClearButton != null) {
			boolean guard = s.job() == Job.GUARD;
			patrolClearButton.visible = guard;
			patrolClearButton.active = guard && GuardJobExecutor.readPatrolPoints(s).length > 0;
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
		ctx.fill(0, 0, this.width, this.height, 0xA0050709);
		int x = (this.width - PANEL_W) / 2;
		int y = (this.height - PANEL_H) / 2;

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

		drawSectionHeader(ctx, x, markerSectionY, "MARKERS");
		drawWaypointRow(ctx, x, markerSectionY + 18, s);
		drawRegionRow(ctx, x, markerSectionY + 18 + ROW_H, s);
		drawMarkerRow(ctx, x, markerSectionY + 18 + ROW_H * 2, "Deposit", s.depositChest());
		drawPatrolRow(ctx, x, patrolRowY + 1, s);

		super.render(ctx, mouseX, mouseY, partialTick);
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
			drawMarkerRow(ctx, panelX, y, "Waypoint", s.waypoint());
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
		Network.getNetworkHandler().sendToServer(new GiveAIMarkerPacketC2S(entity.getId(), mode));
		Minecraft.getInstance().setScreen(null);
	}

	private void toggleRun() {
		Network.getNetworkHandler().sendToServer(new StartStopJobPacketC2S(entity.getId(), !entity.getAIState().running()));
	}

	private void clearPatrol() {
		Network.getNetworkHandler().sendToServer(new ClearPatrolPacketC2S(entity.getId()));
	}
}
