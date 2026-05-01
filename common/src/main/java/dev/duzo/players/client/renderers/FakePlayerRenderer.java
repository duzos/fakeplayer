package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;

public class FakePlayerRenderer extends LivingEntityRenderer<FakePlayerEntity, AvatarRenderState, FakePlayerModel> {
	private final boolean slim;

	public FakePlayerRenderer(EntityRendererProvider.Context context, boolean slim) {
		super(context, new FakePlayerModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim), 0.5F);
		this.slim = slim;

		this.addLayer(new HumanoidArmorLayer<>(this,
			new HumanoidArmorModel<>(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR)),
			new HumanoidArmorModel<>(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR)),
			context.getEquipmentRenderer()));
		this.addLayer(new ItemInHandLayer<>(this));
		this.addLayer(new ArrowLayer<>(this, context));
		this.addLayer(new CustomHeadLayer<>(this, context.getModelSet()));
		this.addLayer(new BeeStingerLayer<>(this, context));
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new FakeAvatarRenderState();
	}

	@Override
	public void extractRenderState(FakePlayerEntity entity, AvatarRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		if (state instanceof FakeAvatarRenderState fake) {
			fake.skinTexture = entity.getSkin();
			fake.isSitting = entity.isSitting();
			fake.slim = entity.isSlim();
			state.skin = new PlayerSkin(fake.skinTexture, null, null, null,
				fake.slim ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, false);
		}
		if (entity.getPhysicalState() == FakePlayerEntity.PhysicalState.LAYING) {
			state.pose = Pose.SLEEPING;
			state.bedOrientation = entity.getDirection();
			state.walkAnimationSpeed = 0;
		}
		if (!entity.isCustomNameVisible()) {
			state.nameTag = null;
		}
	}

	@Override
	public void render(AvatarRenderState state, PoseStack matrices, MultiBufferSource buffer, int packedLight) {
		matrices.pushPose();
		if (state.isBaby) {
			matrices.scale(0.5f, 0.5f, 0.5f);
		} else {
			matrices.scale(0.9375F, 0.9375F, 0.9375F);
		}

		if (state instanceof FakeAvatarRenderState fake && fake.isSitting) {
			matrices.translate(0, -0.5f, 0);
		}

		super.render(state, matrices, buffer, packedLight);
		matrices.popPose();
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		if (state instanceof FakeAvatarRenderState fake && fake.skinTexture != null) {
			return fake.skinTexture;
		}
		return null;
	}

	@Override
	protected void renderNameTag(AvatarRenderState state, Component name, PoseStack stack, MultiBufferSource buffer, int packedLight) {
		if (state.nameTag == null) {
			return;
		}
		super.renderNameTag(state, name, stack, buffer, packedLight);
	}

	public boolean isSlim() {
		return this.slim;
	}
}
