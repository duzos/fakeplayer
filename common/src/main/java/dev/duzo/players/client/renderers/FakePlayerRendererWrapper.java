package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

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
			state.skin = PlayerSkin.insecure(
				new ClientAsset.DownloadedTexture(fake.skinTexture, ""),
				null, null,
				fake.slim ? PlayerModelType.SLIM : PlayerModelType.WIDE);
			if (fake.isSitting) {
				state.isPassenger = true;
				state.y -= 0.5;
			}
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
	public void submit(AvatarRenderState state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera) {
		boolean isSlim = state instanceof FakeAvatarRenderState fake && fake.slim;
		FakePlayerRenderer renderer = isSlim ? this.slim : this.wide;
		renderer.submit(state, stack, collector, camera);
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		if (state instanceof FakeAvatarRenderState fake && fake.skinTexture != null) {
			return fake.skinTexture;
		}
		return null;
	}
}
