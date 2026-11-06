package dev.duzo.players.client.preview;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;

/** The angle is whole degrees on purpose: equal states let the renderer keep its texture between frames. */
public record SchematicPreviewRenderState(SchematicPreviewModel model, int angle, int x0, int y0, int x1, int y1,
                                          float scale, @Nullable ScreenRectangle scissorArea,
                                          @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
	public SchematicPreviewRenderState(SchematicPreviewModel model, int angle, int x0, int y0, int x1, int y1,
	                                   float scale, @Nullable ScreenRectangle scissorArea) {
		this(model, angle, x0, y0, x1, y1, scale, scissorArea,
				PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
	}
}
