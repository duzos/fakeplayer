package dev.duzo.players.entities.ai;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * How a rod's fitted tackle should be drawn, so a modded bobber does not render as the vanilla one.
 * Supplied by the platform's tackle provider along with the rest of the {@link Tackle}, which keeps
 * the texture paths (which are a mod's, not ours) out of the renderer.
 *
 * @param bobberTexture  background layer, null when no bobber is fitted
 * @param bobberOverlay  tinted layer drawn over the background, null to draw the vanilla bobber
 * @param bobberColor    ARGB tint for the overlay, -1 to leave it untinted
 * @param lineColor      ARGB colour for the line back to the fake's hand, -1 for the default
 */
public record TackleLook(
		@Nullable ResourceLocation hookTexture,
		@Nullable ResourceLocation bobberTexture,
		@Nullable ResourceLocation bobberOverlay,
		int bobberColor,
		int lineColor) {

	public static final TackleLook PLAIN = new TackleLook(null, null, null, -1, -1);

	public boolean isPlain() {
		return this.hookTexture == null && this.bobberTexture == null && this.bobberOverlay == null;
	}
}
