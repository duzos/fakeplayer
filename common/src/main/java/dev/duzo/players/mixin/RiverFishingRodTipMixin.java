package dev.duzo.players.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.duzo.players.client.renderers.FishingRodTip;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.riverfishing.client.RodItemRenderer", remap = false)
public abstract class RiverFishingRodTipMixin {
	@Shadow @Final private static float BLANK_AXIS_Y;
	@Shadow @Final private static float BLANK_AXIS_Z;
	@Shadow public static Float blankTipX(String rodKey) { throw new AssertionError(); }

	@Inject(method = "captureTipView", at = @At("HEAD"), remap = false)
	private static void players$captureRodTip(PoseStack pose, String rodKey, ItemDisplayContext context,
	                                         ItemStack rod, CallbackInfo ci) {
		if (context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
				&& context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND) return;
		Float tip = blankTipX(rodKey);
		if (tip != null) FishingRodTip.capture(pose, rod,
				tip / 16F - 0.5F, BLANK_AXIS_Y / 16F - 0.5F, BLANK_AXIS_Z / 16F - 0.5F);
	}
}
