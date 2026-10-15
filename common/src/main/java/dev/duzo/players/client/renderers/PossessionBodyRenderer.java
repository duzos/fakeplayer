package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.PossessionDisguises;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.possession.PossessionBodyEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.UUID;

/**
 * Draws the body a possessing player left behind: their own skin and name, with the armour and held items they had on. All of
 * it is display data synced to the body, so the body itself never carries an item.
 */
public class PossessionBodyRenderer extends LivingEntityRenderer<PossessionBodyEntity, AvatarRenderState, FakePlayerModel> {
	private final FakePlayerRenderer wide;
	private final FakePlayerRenderer slim;

	public PossessionBodyRenderer(EntityRendererProvider.Context context) {
		super(context, new FakePlayerRenderer(context, false).getModel(), 0.5F);
		this.wide = new FakePlayerRenderer(context, false);
		this.slim = new FakePlayerRenderer(context, true);
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new FakeAvatarRenderState();
	}

	@Override
	public void extractRenderState(PossessionBodyEntity entity, AvatarRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTick, this.itemModelResolver);
		state.leftArmPose = state.leftHandItemStack.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
		state.rightArmPose = state.rightHandItemStack.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;

		PlayerSkin skin = ownerSkin(entity.getOwner());
		state.skin = skin;
		if (state instanceof FakeAvatarRenderState fake) {
			fake.skinTexture = skin.body().texturePath();
			fake.slim = skin.model() == PlayerModelType.SLIM;
		}
	}

	private static PlayerSkin ownerSkin(UUID owner) {
		if (owner == null) return DefaultPlayerSkin.getDefaultSkin();
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		PlayerInfo info = connection == null ? null : connection.getPlayerInfo(owner);
		return info == null ? DefaultPlayerSkin.get(owner) : PossessionDisguises.realSkin(info);
	}

	@Override
	public void submit(AvatarRenderState state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera) {
		boolean isSlim = state instanceof FakeAvatarRenderState fake && fake.slim;
		(isSlim ? this.slim : this.wide).submit(state, stack, collector, camera);
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		return state instanceof FakeAvatarRenderState fake ? fake.skinTexture : null;
	}
}
