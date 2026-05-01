package dev.duzo.players.client.model;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;

import dev.duzo.players.client.renderers.FakePlayerRenderState;

public class FakePlayerModel extends PlayerModel {
	public FakePlayerModel(ModelPart part, boolean slim) {
		super(part, slim);
	}

	@Override
	public void setupAnim(PlayerRenderState state) {
		super.setupAnim(state);

		if (state instanceof FakePlayerRenderState fake && fake.isSitting && !state.isPassenger) {
			this.translateSitting();
		}
	}

	private void translateSitting() {
		this.rightArm.xRot += (-(float) Math.PI / 5F);
		this.rightSleeve.xRot += (-(float) Math.PI / 5F);

		this.leftArm.xRot += (-(float) Math.PI / 5F);
		this.leftSleeve.xRot += (-(float) Math.PI / 5F);

		this.rightLeg.xRot = -1.4137167F;
		this.rightLeg.yRot = ((float) Math.PI / 10F);
		this.rightLeg.zRot = 0.07853982F;
		this.rightPants.xRot = -1.4137167F;
		this.rightPants.yRot = ((float) Math.PI / 10F);
		this.rightPants.zRot = 0.07853982F;

		this.leftLeg.xRot = -1.4137167F;
		this.leftLeg.yRot = (-(float) Math.PI / 10F);
		this.leftLeg.zRot = -0.07853982F;
		this.leftPants.xRot = -1.4137167F;
		this.leftPants.yRot = (-(float) Math.PI / 10F);
		this.leftPants.zRot = -0.07853982F;
	}
}
