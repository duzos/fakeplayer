package dev.duzo.players.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

public class FlatButton extends AbstractButton {
	public interface PressHandler {
		void onPress();
	}

	public static final int DEFAULT_BG = 0xFF1A2330;
	public static final int DEFAULT_BG_HOVER = 0xFF243042;
	public static final int DEFAULT_BORDER = 0xFF2A3548;
	public static final int DEFAULT_BORDER_HOVER = 0xFF2EC4FF;
	public static final int DEFAULT_TEXT = 0xFFE6EAEF;
	public static final int DEFAULT_TEXT_HOVER = 0xFFFFFFFF;

	private final PressHandler handler;
	private int bgColor = DEFAULT_BG;
	private int hoverBgColor = DEFAULT_BG_HOVER;
	private int borderColor = DEFAULT_BORDER;
	private int hoverBorderColor = DEFAULT_BORDER_HOVER;
	private int textColor = DEFAULT_TEXT;
	private int hoverTextColor = DEFAULT_TEXT_HOVER;
	private boolean drawBorder = true;
	private boolean drawBackground = true;
	private boolean bold;

	public FlatButton(int x, int y, int w, int h, Component msg, PressHandler handler) {
		super(x, y, w, h, msg);
		this.handler = handler;
	}

	public FlatButton withBg(int bg, int hoverBg) {
		this.bgColor = bg;
		this.hoverBgColor = hoverBg;
		return this;
	}

	public FlatButton withBorder(int border, int hoverBorder) {
		this.borderColor = border;
		this.hoverBorderColor = hoverBorder;
		return this;
	}

	public FlatButton withText(int text, int hoverText) {
		this.textColor = text;
		this.hoverTextColor = hoverText;
		return this;
	}

	public FlatButton noBorder() {
		this.drawBorder = false;
		return this;
	}

	public FlatButton noBackground() {
		this.drawBackground = false;
		return this;
	}

	public FlatButton bold() {
		this.bold = true;
		return this;
	}

	public void setColors(int bg, int hoverBg, int border, int hoverBorder, int text, int hoverText) {
		this.bgColor = bg;
		this.hoverBgColor = hoverBg;
		this.borderColor = border;
		this.hoverBorderColor = hoverBorder;
		this.textColor = text;
		this.hoverTextColor = hoverText;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		handler.onPress();
	}

	@Override
	protected void renderContents(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		boolean hovered = this.isHoveredOrFocused();
		int bg = hovered ? hoverBgColor : bgColor;
		int border = hovered ? hoverBorderColor : borderColor;
		int text = hovered ? hoverTextColor : textColor;

		int x0 = this.getX();
		int y0 = this.getY();
		int x1 = x0 + this.width;
		int y1 = y0 + this.height;

		if (drawBackground) {
			ctx.fill(x0, y0, x1, y1, bg);
		}

		if (drawBorder) {
			ctx.fill(x0, y0, x1, y0 + 1, border);
			ctx.fill(x0, y1 - 1, x1, y1, border);
			ctx.fill(x0, y0, x0 + 1, y1, border);
			ctx.fill(x1 - 1, y0, x1, y1, border);
		}

		if (!this.active) {
			ctx.fill(x0, y0, x1, y1, 0x60000000);
		}

		var font = Minecraft.getInstance().font;
		Component msg = this.getMessage();
		Component drawMsg = bold ? msg.copy().withStyle(s -> s.withBold(true)) : msg;
		int textW = font.width(drawMsg);
		int textX = x0 + (this.width - textW) / 2;
		int textY = y0 + (this.height - 8) / 2 + 1;
		int colorToDraw = this.active ? text : 0xFF6B7787;
		ctx.drawString(font, drawMsg, textX, textY, colorToDraw, false);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		this.defaultButtonNarrationText(out);
	}
}
