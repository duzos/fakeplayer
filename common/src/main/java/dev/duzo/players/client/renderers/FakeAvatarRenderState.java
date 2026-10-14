package dev.duzo.players.client.renderers;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class FakeAvatarRenderState extends AvatarRenderState {
	public Identifier skinTexture;
	public boolean isSitting;
	public boolean slim;
	public List<Component> bubbleRows = List.of();
	public Vec3 bubbleAnchor;
	public int bubbleBase;
	public Component statusMark;
	public int markBase;
}
