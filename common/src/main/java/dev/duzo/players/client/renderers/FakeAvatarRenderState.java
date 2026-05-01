package dev.duzo.players.client.renderers;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

public class FakeAvatarRenderState extends AvatarRenderState {
	public Identifier skinTexture;
	public boolean isSitting;
	public boolean slim;
}
