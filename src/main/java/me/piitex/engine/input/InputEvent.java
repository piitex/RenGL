package me.piitex.engine.input;

public class InputEvent {

    // TODO: Make dedicated class
    public enum Type {
        MOUSE_MOVE,
        MOUSE_BUTTON,
        KEY,
        MOUSE_ENTER,
        CHAR,
        /**
         * Mouse wheel or trackpad scroll. {@code x} and {@code y} hold the raw GLFW xoffset and yoffset rather than a cursor position.
         */
        SCROLL
    }

    public final Type type;
    public final long timestampNanos;
    public final double x;
    public final double y;
    public final int buttonOrKey;
    public final int action;
    /**
     * GLFW modifier bitmask (GLFW_MOD_SHIFT/CONTROL/ALT/SUPER). Only meaningful for KEY events; 0 for every other type.
     */
    public final int mods;

    public InputEvent(Type type, long timestampNanos, double x, double y, int buttonOrKey, int action) {
        this(type, timestampNanos, x, y, buttonOrKey, action, 0);
    }

    public InputEvent(Type type, long timestampNanos, double x, double y, int buttonOrKey, int action, int mods) {
        this.type = type;
        this.timestampNanos = timestampNanos;
        this.x = x;
        this.y = y;
        this.buttonOrKey = buttonOrKey;
        this.action = action;
        this.mods = mods;
    }
}