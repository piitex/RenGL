package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.ui.background.ConstellationBackground;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The mouse's last known absolute window position, bridged the same way
 * {@link CursorManager} bridges the OS cursor. It is a static holder rather than
 * something threaded through every {@link me.piitex.engine.ui.Element}, since
 * {@link me.piitex.engine.Engine} handles one {@link me.piitex.engine.Window} at a
 * time and most Elements have no reason to know where the mouse is. With several windows
 * open, each keeps its own position, and these getters answer for the window the engine is
 * processing: during a window's input, updates and drawing, that window. Hover, click, and
 * drag hit-testing already cover the normal cases (see {@link InputProcessor}). This
 * exists for the rarer case of an Element that needs live mouse position outside those,
 * such as {@link ConstellationBackground}'s mouse-repulsion effect or
 * {@link me.piitex.engine.ui.background.ElectricNodeBackground}'s arcs to the cursor.
 * <p>
 * Unlike {@link CursorManager}, no cross-thread pump is needed here.
 * {@link InputProcessor#processEvents}, which calls {@link #update}, and every
 * {@code update()} and {@code render()} call that reads this all run on the render
 * thread. See {@link me.piitex.engine.Engine}'s render loop. The fields are still marked
 * volatile rather than relying on that remaining true.
 */
public final class MouseTracker {
    // Render thread: the window being processed, and each window's mouse state.
    private static final Map<Window, State> states = new WeakHashMap<>();
    private static volatile State current = new State();

    private static final class State {
        volatile double x = -1;
        volatile double y = -1;
        volatile boolean inWindow = false;
    }

    private MouseTracker() {
    }

    /**
     * Engine use, render thread: makes the getters answer for {@code window} until the next call.
     */
    public static void select(Window window) {
        current = states.computeIfAbsent(window, w -> new State());
    }

    /**
     * Engine use, render thread: drops a closed window's state.
     */
    public static void forget(Window window) {
        State removed = states.remove(window);
        // Without this the getters keep answering with the closed window's last pointer, and whatever draws next (another
        // window, an offscreen capture) sees a mouse that is no longer anywhere.
        if (removed != null && removed == current) current = new State();
    }

    /**
     * Ends a {@link #lend} when closed.
     */
    public interface Loan extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Lends the getters another pointer until the returned loan is closed, for a surface that shows a UI somewhere other than
     * on the window, such as a screen in a 3D scene: while it draws and updates, an element that asks where the mouse is (a
     * table's hovered row, a chart's tooltip, a dropdown list) is told where the pointer is on <em>that</em> surface, in the
     * coordinates it was laid out in, and whether it is on it.
     * <pre>{@code
     * try (var loan = MouseTracker.lend(x, y, true)) {
     *     container.update(delta);
     *     container.render(renderer);
     * }
     * }</pre>
     * Render thread only, like the rest of this class. Loans nest.
     */
    public static Loan lend(double x, double y, boolean inWindow) {
        State previous = current;
        State lent = new State();
        lent.x = x;
        lent.y = y;
        lent.inWindow = inWindow;
        current = lent;
        return () -> current = previous;
    }

    static void update(double x, double y) {
        State state = current;
        state.x = x;
        state.y = y;
        // A cursor-move event is itself proof the mouse is over the window, so this is
        // set here as well as from MOUSE_ENTER. GLFW's enter callback is edge-triggered
        // and never fires if the window opens, or gains a cursor callback, with the
        // mouse already over it without having crossed the boundary. Without this,
        // later movement would not correct that.
        state.inWindow = true;
    }

    static void setInWindow(boolean inWindow) {
        current.inWindow = inWindow;
    }

    /**
     * The mouse's last known X, in absolute window coordinates in logical points. The value is stale, and should not be used, while {@link #isInWindow()} is false.
     */
    public static double getX() {
        return current.x;
    }

    /**
     * As {@link #getX()}, for Y.
     */
    public static double getY() {
        return current.y;
    }

    /**
     * Whether the mouse is currently over the window at all. See {@link me.piitex.engine.input.InputEvent.Type#MOUSE_ENTER}. This goes false once the mouse leaves, so a stale last-known position does not keep acting as though the mouse is still there.
     */
    public static boolean isInWindow() {
        return current.inWindow;
    }
}
