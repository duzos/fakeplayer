package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

public class FakePlayerRenderer extends LivingEntityRenderer<FakePlayerEntity, AvatarRenderState, FakePlayerModel> {
	private final boolean slim;

	public FakePlayerRenderer(EntityRendererProvider.Context context, boolean slim) {
		super(context, new FakePlayerModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim), 0.5F);
		this.slim = slim;

		this.addLayer(new HumanoidArmorLayer<>(this,
			ArmorModelSet.bake(slim ? ModelLayers.PLAYER_SLIM_ARMOR : ModelLayers.PLAYER_ARMOR,
				context.getModelSet(),
				modelPart -> new PlayerModel(modelPart, slim)),
			context.getEquipmentRenderer()));
		this.addLayer(new ItemInHandLayer<>(this));
		this.addLayer(new ArrowLayer<>(this, context));
		this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getPlayerSkinRenderCache()));
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
			state.skin = PlayerSkin.insecure(
				new ClientAsset.DownloadedTexture(fake.skinTexture, ""),
				null, null,
				fake.slim ? PlayerModelType.SLIM : PlayerModelType.WIDE);
			if (fake.isSitting) {
				state.isPassenger = true; // seated pose; the -0.5 lower is applied once in submit()
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

	// AvatarRenderer's equivalent is private and takes an Avatar, which a fake is not, so it is mirrored here.
	static HumanoidModel.ArmPose armPose(FakePlayerEntity entity, HumanoidArm arm) {
		ItemStack main = entity.getItemInHand(InteractionHand.MAIN_HAND);
		ItemStack off = entity.getItemInHand(InteractionHand.OFF_HAND);
		HumanoidModel.ArmPose mainPose = poseFor(entity, main, InteractionHand.MAIN_HAND);
		HumanoidModel.ArmPose offPose = poseFor(entity, off, InteractionHand.OFF_HAND);

		// a two-handed pose owns both arms, so the other one just holds or is empty
		if (mainPose.isTwoHanded()) {
			offPose = off.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
		}

		return arm == entity.getMainArm() ? mainPose : offPose;
	}

	private static HumanoidModel.ArmPose poseFor(FakePlayerEntity entity, ItemStack stack, InteractionHand hand) {
		if (stack.isEmpty()) return HumanoidModel.ArmPose.EMPTY;

		if (!entity.swinging && stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
			return HumanoidModel.ArmPose.CROSSBOW_HOLD;
		}

		if (entity.getUsedItemHand() == hand && entity.getUseItemRemainingTicks() > 0) {
			return switch (stack.getUseAnimation()) {
				case BLOCK -> HumanoidModel.ArmPose.BLOCK;
				case BOW -> HumanoidModel.ArmPose.BOW_AND_ARROW;
				case TRIDENT -> HumanoidModel.ArmPose.THROW_TRIDENT;
				case CROSSBOW -> HumanoidModel.ArmPose.CROSSBOW_CHARGE;
				case SPYGLASS -> HumanoidModel.ArmPose.SPYGLASS;
				case TOOT_HORN -> HumanoidModel.ArmPose.TOOT_HORN;
				case BRUSH -> HumanoidModel.ArmPose.BRUSH;
				case SPEAR -> HumanoidModel.ArmPose.SPEAR;
				default -> HumanoidModel.ArmPose.ITEM;
			};
		}

		return HumanoidModel.ArmPose.ITEM;
	}

	@Override
	public void submit(AvatarRenderState state, PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera) {
		matrices.pushPose();
		if (state.isBaby) {
			matrices.scale(0.5f, 0.5f, 0.5f);
		} else {
			matrices.scale(0.9375F, 0.9375F, 0.9375F);
		}

		if (state instanceof FakeAvatarRenderState fake && fake.isSitting) {
			matrices.translate(0, -0.5f, 0);
		}

		super.submit(state, matrices, collector, camera);
		matrices.popPose();
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		if (state instanceof FakeAvatarRenderState fake && fake.skinTexture != null) {
			return fake.skinTexture;
		}
		return null;
	}

	public boolean isSlim() {
		return this.slim;
	}
}
