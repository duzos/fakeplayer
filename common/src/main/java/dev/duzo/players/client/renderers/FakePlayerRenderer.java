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
		matrices.pushPose();
		if (entity.isBaby()) {
			matrices.scale(0.5f, 0.5f, 0.5f);
		} else {
			matrices.scale(0.9375F, 0.9375F, 0.9375F);
		}

		if (entity.isSitting()) {
			matrices.translate(0, -0.5f, 0);
		}

		// There is no render state on this version: poses live directly on the model, and nothing was
		// ever setting them before this, so a fake drawing a bow / charging a crossbow / winding up a
		// trident rendered with its arms down. Set them here, right before the model uses them, same as
		// the display-item fallback below (an item-use pose wins, otherwise the old ITEM/EMPTY behaviour).
		ItemStack display = entity.getDisplayItem();
		boolean mainIsRight = entity.getMainArm() == HumanoidArm.RIGHT;
		boolean mainHandHasDisplay = !display.isEmpty();
		HumanoidModel<FakePlayerEntity> model = this.getModel();
		model.leftArmPose = resolveArmPose(entity, HumanoidArm.LEFT, !mainIsRight && mainHandHasDisplay);
		model.rightArmPose = resolveArmPose(entity, HumanoidArm.RIGHT, mainIsRight && mainHandHasDisplay);

		super.render(entity, pEntityYaw, pPartialTicks, matrices, pBuffer, pPackedLight);
		matrices.popPose();
	}

	// AvatarRenderer's equivalent (on newer versions) is private and takes an Avatar, which a fake is not,
	// so it is mirrored here.
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

	// An item-use pose (drawing a bow, charging a crossbow, winding up a trident) has to win over the plain
	// "holding something" pose, or the fake shoots with its arms down. Everything else keeps the old
	// behaviour, including the display item a job shows without touching the real equipment slot.
	private static HumanoidModel.ArmPose resolveArmPose(FakePlayerEntity entity, HumanoidArm arm, boolean hasDisplayItem) {
		HumanoidModel.ArmPose pose = armPose(entity, arm);
		if (pose != HumanoidModel.ArmPose.EMPTY && pose != HumanoidModel.ArmPose.ITEM) return pose;

		InteractionHand hand = arm == entity.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		return hasDisplayItem || !entity.getItemInHand(hand).isEmpty()
			? HumanoidModel.ArmPose.ITEM : HumanoidModel.ArmPose.EMPTY;
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

		/** Vanilla {@code ItemInHandLayer.render} early-returns when both real hands are empty,
		 *  which skips {@link #renderArmWithItem} entirely and hides an empty-handed crafter's
		 *  display item. Mirror vanilla's per-arm item selection here so the early return only
		 *  applies when there's truly nothing to show (no real item and no display item);
		 *  renderArmWithItem still does the actual display-item substitution above. */
		@Override
		public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, FakePlayerEntity entity, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
			if (entity.getDisplayItem().isEmpty()) {
				super.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
				return;
			}

			boolean rightHanded = entity.getMainArm() == HumanoidArm.RIGHT;
			ItemStack itemstack = rightHanded ? entity.getOffhandItem() : entity.getMainHandItem();
			ItemStack itemstack1 = rightHanded ? entity.getMainHandItem() : entity.getOffhandItem();

			poseStack.pushPose();
			if (this.getParentModel().young) {
				poseStack.translate(0.0F, 0.75F, 0.0F);
				poseStack.scale(0.5F, 0.5F, 0.5F);
			}

			this.renderArmWithItem(entity, itemstack1, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, HumanoidArm.RIGHT, poseStack, buffer, packedLight);
			this.renderArmWithItem(entity, itemstack, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, HumanoidArm.LEFT, poseStack, buffer, packedLight);
			poseStack.popPose();
		}
	}
}
