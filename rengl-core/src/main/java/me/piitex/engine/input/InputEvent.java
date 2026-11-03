package me.piitex.engine.input;

import me.piitex.engine.utils.flags.GeneratedFragments;

@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
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
        SCROLL,
        /**
         * The input method's composition text changed. {@link #preedit} holds it; every other field is unused.
         */
        PREEDIT
    }

    public final Type type;
    public final long timestampNanos;
    public final double x;
    public final double y;
    public final int buttonOrKey;
    public final int action;
    /**
     * GLFW modifier bitmask (GLFW_MOD_SHIFT/CONTROL/ALT/SUPER). Only meaningful for KEY and MOUSE_BUTTON events; 0 for every other type.
     */
    public final int mods;
    /**
     * The composition an input method is showing. Only set for PREEDIT events.
     */
    public final ImePreedit preedit;

    public InputEvent(long timestampNanos, ImePreedit preedit) {
        this(Type.PREEDIT, timestampNanos, 0, 0, -1, -1, 0, preedit);
    }

    public InputEvent(Type type, long timestampNanos, double x, double y, int buttonOrKey, int action) {
        this(type, timestampNanos, x, y, buttonOrKey, action, 0);
    }

    public InputEvent(Type type, long timestampNanos, double x, double y, int buttonOrKey, int action, int mods) {
        this(type, timestampNanos, x, y, buttonOrKey, action, mods, null);
    }

    private InputEvent(Type type, long timestampNanos, double x, double y, int buttonOrKey, int action, int mods, ImePreedit preedit) {
        this.preedit = preedit;
        this.type = type;
        this.timestampNanos = timestampNanos;
        this.x = x;
        this.y = y;
        this.buttonOrKey = buttonOrKey;
        this.action = action;
        this.mods = mods;
    }
}