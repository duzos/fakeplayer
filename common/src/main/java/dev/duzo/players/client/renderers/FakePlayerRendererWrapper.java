package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;

public class FakePlayerRendererWrapper extends LivingEntityRenderer<FakePlayerEntity, FakePlayerModel> {
	private final EntityRendererProvider.Context context;

	public FakePlayerRendererWrapper(EntityRendererProvider.Context context) {
		super(context, null, 0.5F);

		this.context = context;
	}

	@Override
	public void render(FakePlayerEntity entity, float yaw, float partialTicks, PoseStack stack, MultiBufferSource buffer, int packedLight) {
		FakePlayerRenderer renderer = new FakePlayerRenderer(context, entity.isSlim()); // Create the actual renderer
		renderer.render(entity, yaw, partialTicks, stack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(FakePlayerEntity entity) {
		return entity.getSkin();
	}
}
