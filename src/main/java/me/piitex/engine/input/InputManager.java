package me.piitex.engine.input;

import me.piitex.engine.utils.flags.GeneratedFragments;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class InputManager {
    private final Queue<InputEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private double lastMouseX;
    private double lastMouseY;

    public void onCursorPos(long window, double xpos, double ypos) {
        long time = System.nanoTime();
        eventQueue.offer(new InputEvent(InputEvent.Type.MOUSE_MOVE, time, xpos, ypos, -1, -1));
    }

    public void onMouseButton(long window, int button, int action, int mods) {
        long time = System.nanoTime();
        eventQueue.offer(new InputEvent(InputEvent.Type.MOUSE_BUTTON, time, 0, 0, button, action, mods));
    }

    public void onKey(long window, int key, int scancode, int action, int mods) {
        long time = System.nanoTime();
        eventQueue.offer(new InputEvent(InputEvent.Type.KEY, time, 0, 0, key, action, mods));
    }

    /**
     * GLFW's char callback, fired for typed Unicode text already resolved against the OS
     * keyboard layout, shift state, and dead keys, unlike {@link #onKey}, which reports
     * raw physical key codes. This is the correct source for text field input.
     * {@link #onKey} should be used only for non-printable control keys such as
     * backspace, the arrows, and enter.
     */
    public void onChar(long window, int codepoint) {
        long time = System.nanoTime();
        eventQueue.offer(new InputEvent(InputEvent.Type.CHAR, time, 0, 0, codepoint, 0));
    }

    public void onCursorEnter(long window, boolean entered) {
        long time = System.nanoTime();
        eventQueue.offer(new InputEvent(InputEvent.Type.MOUSE_ENTER, time, 0, 0, -1, entered ? 1 : 0));
    }

    /**
     * GLFW's scroll callback. {@code xoffset} and {@code yoffset} are wheel notches or trackpad deltas rather than a position, so they are carried in the event's x and y fields rather than as a cursor location.
     */
    public void onScroll(long window, double xoffset, double yoffset) {
        long time = System.nanoTime();
        eventQueue.offer(new InputEvent(InputEvent.Type.SCROLL, time, xoffset, yoffset, -1, -1));
    }

    /**
     * Main thread only. Reads the names the printable keys have on the current keyboard layout, for
     * {@link Binding#getDisplayName()}. The engine calls this when the window opens and whenever it gains focus, since
     * the layout may have been switched in between.
     */
    public void refreshKeyNames() {
        KeyNames.refreshLayout();
    }

    public Queue<InputEvent> getEventQueue() {
        return eventQueue;
    }
}