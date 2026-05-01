package dev.duzo.players.client.renderers;

import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;

public class FakePlayerRenderState extends PlayerRenderState {
	public ResourceLocation skinTexture;
	public boolean isSitting;
	public boolean slim;
}
