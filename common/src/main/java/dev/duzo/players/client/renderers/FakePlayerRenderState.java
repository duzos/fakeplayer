package dev.duzo.players.client.renderers;

import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class FakePlayerRenderState extends PlayerRenderState {
	public ResourceLocation skinTexture;
	public boolean isSitting;
	public boolean slim;
	public List<Component> bubbleRows = List.of();
	public Vec3 bubbleAnchor;
	public int bubbleBase;
}
