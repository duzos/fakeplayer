package dev.duzo.fppilot;

/**
 * Modifier keys a synthetic click carries. Screen reads them from the window, not the click, so the
 * ScreenKeyState mixin answers from here. Non-zero only inside the pilot's own click, on the client thread.
 */
public final class HeldModifiers {
    /** GLFW modifier bits: 1 shift, 2 control, 4 alt. */
    public static int bits;

    private HeldModifiers() {}
}
