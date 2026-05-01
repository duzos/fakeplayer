package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;

public class FakePlayerRendererWrapper extends LivingEntityRenderer<FakePlayerEntity, AvatarRenderState, FakePlayerModel> {
	private final FakePlayerRenderer wide;
	private final FakePlayerRenderer slim;

	public FakePlayerRendererWrapper(EntityRendererProvider.Context context) {
		super(context, new FakePlayerRenderer(context, false).getModel(), 0.5F);
		this.wide = new FakePlayerRenderer(context, false);
		this.slim = new FakePlayerRenderer(context, true);
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
	public void render(AvatarRenderState state, PoseStack stack, MultiBufferSource buffer, int packedLight) {
		boolean isSlim = state instanceof FakeAvatarRenderState fake && fake.slim;
		FakePlayerRenderer renderer = isSlim ? this.slim : this.wide;
		renderer.render(state, stack, buffer, packedLight);
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		if (state instanceof FakeAvatarRenderState fake && fake.skinTexture != null) {
			return fake.skinTexture;
		}
		return null;
	}
}
