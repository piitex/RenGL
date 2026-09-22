package me.piitex.engine.input;

import me.piitex.engine.ui.background.ConstellationBackground;

/**
 * The mouse's last known absolute window position, bridged the same way
 * {@link CursorManager} bridges the OS cursor. It is a static holder rather than
 * something threaded through every {@link me.piitex.engine.ui.Element}, since
 * {@link me.piitex.engine.Engine} drives only one {@link me.piitex.engine.Window} at a
 * time and most Elements have no reason to know where the mouse is. Hover, click, and
 * drag hit-testing already cover the normal cases (see {@link InputProcessor}). This
 * exists for the rarer case of an Element that needs live mouse position outside those,
 * such as {@link ConstellationBackground}'s mouse-repulsion effect.
 * <p>
 * Unlike {@link CursorManager}, no cross-thread pump is needed here.
 * {@link InputProcessor#processEvents}, which calls {@link #update}, and every
 * {@code update()} and {@code render()} call that reads this all run on the render
 * thread. See {@link me.piitex.engine.Engine}'s render loop. The fields are still marked
 * volatile rather than relying on that remaining true.
 */
public final class MouseTracker {
    private static volatile double x = -1;
    private static volatile double y = -1;
    private static volatile boolean inWindow = false;

    private MouseTracker() {
    }

    static void update(double x, double y) {
        MouseTracker.x = x;
        MouseTracker.y = y;
        // A cursor-move event is itself proof the mouse is over the window, so this is
        // set here as well as from MOUSE_ENTER. GLFW's enter callback is edge-triggered
        // and never fires if the window opens, or gains a cursor callback, with the
        // mouse already over it without having crossed the boundary. Without this,
        // later movement would not correct that.
        inWindow = true;
    }

    static void setInWindow(boolean inWindow) {
        MouseTracker.inWindow = inWindow;
    }

    /**
     * The mouse's last known X, in absolute window coordinates in logical points. The value is stale, and should not be used, while {@link #isInWindow()} is false.
     */
    public static double getX() {
        return x;
    }

    /**
     * As {@link #getX()}, for Y.
     */
    public static double getY() {
        return y;
    }

    /**
     * Whether the mouse is currently over the window at all. See {@link me.piitex.engine.input.InputEvent.Type#MOUSE_ENTER}. This goes false once the mouse leaves, so a stale last-known position does not keep acting as though the mouse is still there.
     */
    public static boolean isInWindow() {
        return inWindow;
    }
}
