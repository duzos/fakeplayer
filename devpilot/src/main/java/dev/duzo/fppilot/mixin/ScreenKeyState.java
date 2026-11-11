package dev.duzo.fppilot.mixin;

import dev.duzo.fppilot.HeldModifiers;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets a synthetic click carry modifier keys: see {@link HeldModifiers}. */
@Mixin(Screen.class)
public abstract class ScreenKeyState {
    @Inject(method = "hasShiftDown", at = @At("HEAD"), cancellable = true)
    private static void fppilot$shift(CallbackInfoReturnable<Boolean> cir) {
        if ((HeldModifiers.bits & 1) != 0) cir.setReturnValue(true);
    }

    @Inject(method = "hasControlDown", at = @At("HEAD"), cancellable = true)
    private static void fppilot$control(CallbackInfoReturnable<Boolean> cir) {
        if ((HeldModifiers.bits & 2) != 0) cir.setReturnValue(true);
    }

    @Inject(method = "hasAltDown", at = @At("HEAD"), cancellable = true)
    private static void fppilot$alt(CallbackInfoReturnable<Boolean> cir) {
        if ((HeldModifiers.bits & 4) != 0) cir.setReturnValue(true);
    }
}
