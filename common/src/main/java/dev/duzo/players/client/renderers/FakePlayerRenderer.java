package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class FakePlayerRenderer extends LivingEntityRenderer<FakePlayerEntity, FakePlayerModel> {
	public FakePlayerRenderer(EntityRendererProvider.Context context, boolean slim) {
		super(context, new FakePlayerModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim), 0.5F);

		this.addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR)), new HumanoidArmorModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
		this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
		this.addLayer(new ArrowLayer<>(context, this));
		this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
		this.addLayer(new BeeStingerLayer<>(this));
	}

	@Override
	public void render(FakePlayerEntity entity, float pEntityYaw, float pPartialTicks, PoseStack matrices, MultiBufferSource pBuffer, int pPackedLight) {
		matrices.pushPose();
		if (entity.isBaby()) {
			matrices.scale(0.5f, 0.5f, 0.5f);
		} else {
			matrices.scale(0.9375F, 0.9375F, 0.9375F);
		}

		if (entity.isSitting()) {
			matrices.translate(0, -0.5f, 0);
		}

		super.render(entity, pEntityYaw, pPartialTicks, matrices, pBuffer, pPackedLight);
		matrices.popPose();
	}


	@Override
	public ResourceLocation getTextureLocation(FakePlayerEntity entity) {
		return entity.getSkin();
	}

	@Override
	protected void renderNameTag(FakePlayerEntity entity, Component name, PoseStack stack, MultiBufferSource buffer, int packedLight, float partialTick) {
		if (!entity.isCustomNameVisible()) {
			return;
		}

		super.renderNameTag(entity, name, stack, buffer, packedLight, partialTick);
	}
}
