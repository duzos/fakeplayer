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
		// arm poses live on the model here (no render state on this version), and this renderer is rebuilt
		// every frame by the wrapper, so its default HumanoidModel.ArmPose.EMPTY needs setting each time -
		// otherwise a fake drawing a bow / charging a crossbow / winding up a trident shows arms down
		this.getModel().rightArmPose = armPose(entity, HumanoidArm.RIGHT);
		this.getModel().leftArmPose = armPose(entity, HumanoidArm.LEFT);

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
	protected void renderNameTag(FakePlayerEntity entity, Component name, PoseStack stack, MultiBufferSource buffer, int packedLight, float partialTick) {
		if (!entity.isCustomNameVisible()) {
			return;
		}

		super.renderNameTag(entity, name, stack, buffer, packedLight, partialTick);
	}

	// No AvatarRenderState on this version - poses are set directly on the model in render() above, using
	// this same lookup. Mirrors AvatarRenderer's equivalent, which is private and takes an Avatar (a fake is
	// not one).
	static HumanoidModel.ArmPose armPose(FakePlayerEntity entity, HumanoidArm arm) {
		ItemStack main = entity.getItemInHand(InteractionHand.MAIN_HAND);
		ItemStack off = entity.getItemInHand(InteractionHand.OFF_HAND);
		HumanoidModel.ArmPose mainPose = poseFor(entity, main, InteractionHand.MAIN_HAND);
		HumanoidModel.ArmPose offPose = poseFor(entity, off, InteractionHand.OFF_HAND);

		// a two-handed pose owns both arms, so the other one just falls back to empty (this version never
		// gave a plain held item its own arm-raise pose, so there is no ITEM fallback to preserve here)
		if (mainPose.isTwoHanded()) {
			offPose = HumanoidModel.ArmPose.EMPTY;
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
				default -> HumanoidModel.ArmPose.EMPTY;
			};
		}

		return HumanoidModel.ArmPose.EMPTY;
	}

	/** Swaps in a job's display item for the arm matching the entity's main hand, without ever
	 *  touching the real MAINHAND slot. This renderer extracts per-frame from the entity rather
	 *  than a baked render state, so the hand layer itself is the hook point. */
	private static class DisplayItemInHandLayer extends ItemInHandLayer<FakePlayerEntity, FakePlayerModel> {
		DisplayItemInHandLayer(RenderLayerParent<FakePlayerEntity, FakePlayerModel> parent, ItemInHandRenderer itemInHandRenderer) {
			super(parent, itemInHandRenderer);
		}

		/** Vanilla {@code ItemInHandLayer.render} early-returns when both real hands are empty, which is the
		 *  normal case for a crafter (it never actually holds the ingredient, only displays it) - so this
		 *  reimplements vanilla's per-arm dispatch, substituting the display item for the main-hand item
		 *  whenever one is set. The off-hand path is left byte-for-byte identical to vanilla. */
		@Override
		public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, FakePlayerEntity entity, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
			ItemStack display = entity.getDisplayItem();
			if (display.isEmpty()) {
				super.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
				return;
			}

			ItemStack offHandItem = entity.getOffhandItem();
			boolean mainIsRight = entity.getMainArm() == HumanoidArm.RIGHT;
			ItemStack rightArmItem = mainIsRight ? display : offHandItem;
			ItemStack leftArmItem = mainIsRight ? offHandItem : display;

			poseStack.pushPose();
			if (getParentModel().young) {
				float scale = 0.5f;
				poseStack.translate(0, 0.75f, 0);
				poseStack.scale(scale, scale, scale);
			}
			renderArmWithItem(entity, rightArmItem, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, HumanoidArm.RIGHT, poseStack, buffer, packedLight);
			renderArmWithItem(entity, leftArmItem, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, HumanoidArm.LEFT, poseStack, buffer, packedLight);
			poseStack.popPose();
		}
	}
}
