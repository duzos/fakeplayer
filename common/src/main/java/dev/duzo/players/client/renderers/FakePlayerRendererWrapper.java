package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.model.FakePlayerModel;
import dev.duzo.players.entities.FakePlayerEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class FakePlayerRendererWrapper extends LivingEntityRenderer<FakePlayerEntity, PlayerRenderState, FakePlayerModel> {
	private final FakePlayerRenderer wide;
	private final FakePlayerRenderer slim;

	public FakePlayerRendererWrapper(EntityRendererProvider.Context context) {
		super(context, new FakePlayerRenderer(context, false).getModel(), 0.5F);
		this.wide = new FakePlayerRenderer(context, false);
		this.slim = new FakePlayerRenderer(context, true);
	}

	@Override
	public PlayerRenderState createRenderState() {
		return new FakePlayerRenderState();
	}

	@Override
	public void extractRenderState(FakePlayerEntity entity, PlayerRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTick, this.itemModelResolver);

		// A job (e.g. the crafter placing an ingredient) can ask to show a display item in the main
		// hand without ever touching the real MAINHAND equipment slot. Swap it in after the vanilla
		// extraction above has already baked the real held items, so an empty display item just
		// leaves the real held item showing.
		boolean mainIsRight = state.mainArm == HumanoidArm.RIGHT;
		ItemStack display = entity.getDisplayItem();
		boolean mainHandHasDisplay = !display.isEmpty();
		if (mainHandHasDisplay) {
			ItemDisplayContext context = mainIsRight ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
			ItemStackRenderState target = mainIsRight ? state.rightHandItem : state.leftHandItem;
			this.itemModelResolver.updateForLiving(target, display, context, entity);
		}

		state.leftArmPose = resolveArmPose(entity, HumanoidArm.LEFT, !mainIsRight && mainHandHasDisplay);
		state.rightArmPose = resolveArmPose(entity, HumanoidArm.RIGHT, mainIsRight && mainHandHasDisplay);

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

	// An item-use pose (drawing a bow, charging a crossbow, winding up a trident) has to win over the plain
	// "holding something" pose, or the fake shoots with its arms down. Everything else keeps the old behaviour,
	// including the display item a job shows without touching the real equipment slot.
	private static HumanoidModel.ArmPose resolveArmPose(FakePlayerEntity entity, HumanoidArm arm, boolean hasDisplayItem) {
		HumanoidModel.ArmPose pose = FakePlayerRenderer.armPose(entity, arm);
		if (pose != HumanoidModel.ArmPose.EMPTY && pose != HumanoidModel.ArmPose.ITEM) return pose;

		return hasDisplayItem || !entity.getItemHeldByArm(arm).isEmpty()
			? HumanoidModel.ArmPose.ITEM : HumanoidModel.ArmPose.EMPTY;
	}

	@Override
	public void render(PlayerRenderState state, PoseStack stack, MultiBufferSource buffer, int packedLight) {
		boolean isSlim = state instanceof FakePlayerRenderState fake && fake.slim;
		FakePlayerRenderer renderer = isSlim ? this.slim : this.wide;
		renderer.render(state, stack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(PlayerRenderState state) {
		if (state instanceof FakePlayerRenderState fake && fake.skinTexture != null) {
			return fake.skinTexture;
		}
		return null;
	}
}
