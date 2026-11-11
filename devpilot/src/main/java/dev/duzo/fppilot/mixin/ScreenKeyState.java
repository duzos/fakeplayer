package dev.duzo.fppilot.mixin;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Screen reads modifier keys from the window rather than from the click, so a synthetic click carries
 * its modifiers by making these answer true while it runs. {@link #modifiers} is non-zero only inside
 * the pilot's own click and is set and cleared on the client thread.
 */
@Mixin(Screen.class)
public abstract class ScreenKeyState {
    /** GLFW modifier bits: 1 shift, 2 control, 4 alt. */
    public static int modifiers;

    @Inject(method = "hasShiftDown", at = @At("HEAD"), cancellable = true)
    private static void fppilot$shift(CallbackInfoReturnable<Boolean> cir) {
        if ((modifiers & 1) != 0) cir.setReturnValue(true);
    }

    @Inject(method = "hasControlDown", at = @At("HEAD"), cancellable = true)
    private static void fppilot$control(CallbackInfoReturnable<Boolean> cir) {
        if ((modifiers & 2) != 0) cir.setReturnValue(true);
    }

    @Inject(method = "hasAltDown", at = @At("HEAD"), cancellable = true)
    private static void fppilot$alt(CallbackInfoReturnable<Boolean> cir) {
        if ((modifiers & 4) != 0) cir.setReturnValue(true);
    }
}
