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
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.*;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class FakePlayerRenderer extends LivingEntityRenderer<FakePlayerEntity, FakePlayerModel> {
	public FakePlayerRenderer(EntityRendererProvider.Context context, boolean slim) {
		super(context, new FakePlayerModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim), 0.5F);

		this.addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR)), new HumanoidArmorModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
		this.addLayer(new DisplayItemInHandLayer(this, context.getItemInHandRenderer()));
		this.addLayer(new ArrowLayer<>(context, this));
		this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
		this.addLayer(new BeeStingerLayer<>(this));
	}

	@Override
	public void render(FakePlayerEntity entity, float pEntityYaw, float pPartialTicks, PoseStack matrices, MultiBufferSource pBuffer, int pPackedLight) {
		applyArmPoses(entity, this.getModel());

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
	protected void renderNameTag(FakePlayerEntity entity, Component name, PoseStack stack, MultiBufferSource buffer, int p_114502_) {
		if (!entity.isCustomNameVisible()) {
			return;
		}

		super.renderNameTag(entity, name, stack, buffer, p_114502_);
	}

	// There is no render state on this version: arm poses live directly on the model, and nothing was
	// setting them before this, so a fake drawing a bow or winding up a trident rendered with its arms
	// down. Vanilla's own PlayerRenderer.setModelProperties/getArmPose does this for a real player but is
	// private, so the same logic is mirrored here against the fake's actual held items and use-item state.
	private static void applyArmPoses(FakePlayerEntity entity, FakePlayerModel model) {
		HumanoidModel.ArmPose mainPose = poseFor(entity, InteractionHand.MAIN_HAND);
		HumanoidModel.ArmPose offPose = poseFor(entity, InteractionHand.OFF_HAND);

		// a two-handed pose owns both arms, so the other one just holds or is empty
		if (mainPose.isTwoHanded()) {
			offPose = entity.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
		}

		if (entity.getMainArm() == HumanoidArm.RIGHT) {
			model.rightArmPose = mainPose;
			model.leftArmPose = offPose;
		} else {
			model.rightArmPose = offPose;
			model.leftArmPose = mainPose;
		}
	}

	private static HumanoidModel.ArmPose poseFor(FakePlayerEntity entity, InteractionHand hand) {
		ItemStack stack = entity.getItemInHand(hand);
		if (stack.isEmpty()) return HumanoidModel.ArmPose.EMPTY;

		if (entity.getUsedItemHand() == hand && entity.getUseItemRemainingTicks() > 0) {
			return switch (stack.getUseAnimation()) {
				case BLOCK -> HumanoidModel.ArmPose.BLOCK;
				case BOW -> HumanoidModel.ArmPose.BOW_AND_ARROW;
				case SPEAR -> HumanoidModel.ArmPose.THROW_SPEAR;
				case CROSSBOW -> HumanoidModel.ArmPose.CROSSBOW_CHARGE;
				case SPYGLASS -> HumanoidModel.ArmPose.SPYGLASS;
				case TOOT_HORN -> HumanoidModel.ArmPose.TOOT_HORN;
				case BRUSH -> HumanoidModel.ArmPose.BRUSH;
				default -> HumanoidModel.ArmPose.ITEM;
			};
		}

		if (!entity.swinging && stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
			return HumanoidModel.ArmPose.CROSSBOW_HOLD;
		}

		return HumanoidModel.ArmPose.ITEM;
	}

	/** Swaps in a job's display item for the arm matching the entity's main hand, without ever
	 *  touching the real MAINHAND slot. This renderer extracts per-frame from the entity rather
	 *  than a baked render state, so the hand layer itself is the hook point. */
	private static class DisplayItemInHandLayer extends ItemInHandLayer<FakePlayerEntity, FakePlayerModel> {
		DisplayItemInHandLayer(RenderLayerParent<FakePlayerEntity, FakePlayerModel> parent, ItemInHandRenderer itemInHandRenderer) {
			super(parent, itemInHandRenderer);
		}

		@Override
		protected void renderArmWithItem(LivingEntity entity, ItemStack itemStack, ItemDisplayContext context, HumanoidArm arm, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
			if (entity instanceof FakePlayerEntity fake && arm == fake.getMainArm()) {
				ItemStack display = fake.getDisplayItem();
				if (!display.isEmpty()) itemStack = display;
			}
			super.renderArmWithItem(entity, itemStack, context, arm, poseStack, buffer, packedLight);
		}

		/** Vanilla {@code ItemInHandLayer.render} early-returns when both real hands are empty, so it
		 *  never reaches {@link #renderArmWithItem} for the normal empty-handed crafter. Mirror vanilla's
		 *  per-arm item selection here, but skip that early return whenever a display item is set: the
		 *  substitution above happens inside renderArmWithItem, so real-hand items are still what's passed
		 *  in below, and a fake that already holds something in its main hand is never double-rendered. */
		@Override
		public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, FakePlayerEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float partialTicks) {
			if (entity.getDisplayItem().isEmpty()) {
				super.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, partialTicks);
				return;
			}

			boolean mainArmRight = entity.getMainArm() == HumanoidArm.RIGHT;
			ItemStack mainHandItem = entity.getMainHandItem();
			ItemStack offhandItem = entity.getOffhandItem();
			ItemStack leftItem = mainArmRight ? offhandItem : mainHandItem;
			ItemStack rightItem = mainArmRight ? mainHandItem : offhandItem;

			poseStack.pushPose();
			if (this.getParentModel().young) {
				poseStack.translate(0.0F, 0.75F, 0.0F);
				poseStack.scale(0.5F, 0.5F, 0.5F);
			}

			this.renderArmWithItem(entity, rightItem, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, HumanoidArm.RIGHT, poseStack, buffer, packedLight);
			this.renderArmWithItem(entity, leftItem, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, HumanoidArm.LEFT, poseStack, buffer, packedLight);

			poseStack.popPose();
		}
	}
}
