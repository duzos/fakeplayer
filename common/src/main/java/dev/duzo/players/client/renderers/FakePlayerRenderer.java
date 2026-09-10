package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class FakePlayerRenderer extends LivingEntityRenderer<FakePlayerEntity, PlayerRenderState, FakePlayerModel> {
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
				// this branch's ItemUseAnimation has no TRIDENT constant - both a trident throw and a
				// spear windup report SPEAR, and the matching model pose is THROW_SPEAR, not THROW_TRIDENT
				case SPEAR -> HumanoidModel.ArmPose.THROW_SPEAR;
				case CROSSBOW -> HumanoidModel.ArmPose.CROSSBOW_CHARGE;
				case SPYGLASS -> HumanoidModel.ArmPose.SPYGLASS;
				case TOOT_HORN -> HumanoidModel.ArmPose.TOOT_HORN;
				case BRUSH -> HumanoidModel.ArmPose.BRUSH;
				default -> HumanoidModel.ArmPose.ITEM;
			};
		}

		return HumanoidModel.ArmPose.ITEM;
	}

	@Override
	public void render(PlayerRenderState state, PoseStack matrices, MultiBufferSource buffer, int packedLight) {
		matrices.pushPose();
		if (state.isBaby) {
			matrices.scale(0.5f, 0.5f, 0.5f);
		} else {
			matrices.scale(0.9375F, 0.9375F, 0.9375F);
		}

		if (state instanceof FakePlayerRenderState fake && fake.isSitting) {
			matrices.translate(0, -0.5f, 0);
		}

		super.render(state, matrices, buffer, packedLight);
		matrices.popPose();
	}

	@Override
	public ResourceLocation getTextureLocation(PlayerRenderState state) {
		if (state instanceof FakePlayerRenderState fake && fake.skinTexture != null) {
			return fake.skinTexture;
		}
		return null;
	}

	@Override
	protected void renderNameTag(PlayerRenderState state, Component name, PoseStack stack, MultiBufferSource buffer, int packedLight) {
		if (state.nameTag == null) {
			return;
		}
		super.renderNameTag(state, name, stack, buffer, packedLight);
	}

	public boolean isSlim() {
		return this.slim;
	}
}
