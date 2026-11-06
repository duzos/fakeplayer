package dev.duzo.players.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.entities.possession.PossessionBodyEntity;
import dev.duzo.players.client.PossessionDisguises;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Draws the body a possessing player left behind: their own skin and name, with the armour and held items they had on. All of
 * it is display data synced to the body, so the body itself never carries an item.
 */
public class PossessionBodyRenderer extends LivingEntityRenderer<PossessionBodyEntity, PlayerModel<PossessionBodyEntity>> {
	private final Variant wide;
	private final Variant slim;

	public PossessionBodyRenderer(EntityRendererProvider.Context context) {
		super(context, null, 0.5F);
		this.wide = new Variant(context, false);
		this.slim = new Variant(context, true);
	}

	/** A player's real skin, whatever disguise is on them: the body is drawn with the skin its owner really has. */
	private static PossessionDisguises.Skin ownerSkin(UUID owner) {
		UUID id = owner == null ? new UUID(0L, 0L) : owner;
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		PlayerInfo info = connection == null || owner == null ? null : connection.getPlayerInfo(owner);
		if (info != null) return PossessionDisguises.realSkin(info);
		return new PossessionDisguises.Skin(DefaultPlayerSkin.getDefaultSkin(id), "slim".equals(DefaultPlayerSkin.getSkinModelName(id)));
	}

	@Override
	public void render(PossessionBodyEntity entity, float yaw, float partialTicks, PoseStack stack, MultiBufferSource buffer, int packedLight) {
		(ownerSkin(entity.getOwner()).slim() ? this.slim : this.wide).render(entity, yaw, partialTicks, stack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(PossessionBodyEntity entity) {
		return ownerSkin(entity.getOwner()).texture();
	}

	/** One player model per arm width, built once, since the model cannot change shape after it is baked. */
	private static final class Variant extends LivingEntityRenderer<PossessionBodyEntity, PlayerModel<PossessionBodyEntity>> {
		Variant(EntityRendererProvider.Context context, boolean slim) {
			super(context, new PlayerModel<>(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim), 0.5F);
			this.addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR)),
					new HumanoidArmorModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
			this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
			this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
		}

		@Override
		public void render(PossessionBodyEntity entity, float yaw, float partialTicks, PoseStack stack, MultiBufferSource buffer, int packedLight) {
			PlayerModel<PossessionBodyEntity> model = this.getModel();
			model.rightArmPose = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
			model.leftArmPose = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
			stack.pushPose();
			try {
				stack.scale(0.9375F, 0.9375F, 0.9375F);
				super.render(entity, yaw, partialTicks, stack, buffer, packedLight);
			} finally {
				stack.popPose();
			}
		}

		@Override
		public ResourceLocation getTextureLocation(PossessionBodyEntity entity) {
			return ownerSkin(entity.getOwner()).texture();
		}
	}
}
