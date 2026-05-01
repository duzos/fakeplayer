package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;

public class FakePlayerRendererWrapper extends LivingEntityRenderer<FakePlayerEntity, PlayerRenderState, FakePlayerModel> {
	private final FakePlayerRenderer wide;
	private final FakePlayerRenderer slim;

	public FakePlayerRendererWrapper(EntityRendererProvider.Context context) {
		super(context, new FakePlayerRenderer(context, false).getModel(), 0.5F);
		this.wide = new FakePlayerRenderer(context, false);
		this.slim = new FakePlayerRenderer(context, true);
	}

	@Override
	public PlayerRenderState createRenderState() {
		return new FakePlayerRenderState();
	}

	@Override
	public void extractRenderState(FakePlayerEntity entity, PlayerRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		if (state instanceof FakePlayerRenderState fake) {
			fake.skinTexture = entity.getSkin();
			fake.isSitting = entity.isSitting();
			fake.slim = entity.isSlim();
		}
		if (!entity.isCustomNameVisible()) {
			state.nameTag = null;
		}
	}

	@Override
	public void render(PlayerRenderState state, PoseStack stack, MultiBufferSource buffer, int packedLight) {
		boolean isSlim = state instanceof FakePlayerRenderState fake && fake.slim;
		FakePlayerRenderer renderer = isSlim ? this.slim : this.wide;
		renderer.render(state, stack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(PlayerRenderState state) {
		if (state instanceof FakePlayerRenderState fake && fake.skinTexture != null) {
			return fake.skinTexture;
		}
		return null;
	}
}
