package dev.duzo.players.client.model;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import dev.duzo.players.client.renderers.FakeAvatarRenderState;

public class FakePlayerModel extends PlayerModel {
	public FakePlayerModel(ModelPart part, boolean slim) {
		super(part, slim);
	}

	@Override
	public void setupAnim(AvatarRenderState state) {
		super.setupAnim(state);

		if (state instanceof FakeAvatarRenderState fake && fake.isSitting && !state.isPassenger) {
			this.rightArm.xRot += (-(float) Math.PI / 5F);
			this.leftArm.xRot += (-(float) Math.PI / 5F);

			this.rightLeg.xRot = -1.4137167F;
			this.rightLeg.yRot = ((float) Math.PI / 10F);
			this.rightLeg.zRot = 0.07853982F;

			this.leftLeg.xRot = -1.4137167F;
			this.leftLeg.yRot = (-(float) Math.PI / 10F);
			this.leftLeg.zRot = -0.07853982F;
		}
	}
}
