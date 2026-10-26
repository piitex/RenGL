package me.piitex.engine.input;

import me.piitex.engine.math.Vec2;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.Locale;

/**
 * One game controller as a window's {@link Input} sees it: Xbox, PlayStation, Switch Pro and the many pads that look
 * like one of them. Get it from {@link Input#getGamepad()} (the first one plugged in) or {@link Input#getGamepad(int)}
 * (a slot, 0 to 15, in the order the OS found them). A slot with nothing in it still gives a {@code Gamepad}, one that
 * is not {@link #isConnected() connected} and reads as untouched, so game code needs no null checks.
 * <pre>{@code
 * Gamepad pad = getWindow().getInput().getGamepad();
 * if (pad.isJustPressed(Gamepad.Button.A)) jump();
 * Vec2 move = pad.getLeftStick();          // x right, y down, never longer than 1
 * float throttle = pad.getRightTrigger();  // 0 to 1
 * }</pre>
 * Most games should not poll a pad but add {@link Binding#gamepad gamepad bindings} to an {@link InputContext}, which
 * gives the player rebinding for free and treats a keyboard key and a button as the same action.
 * <h2>Layout</h2>
 * Every pad is mapped to the layout of an Xbox controller, by the mapping database GLFW ships (more can be added with
 * {@link Gamepads#loadMappings}). So {@link Button#A} is the button at the bottom of the face diamond: Cross on a
 * PlayStation pad, and the one labelled B on a Switch pad. {@link #getLayout()} says which family the pad is from, for
 * showing the button names the player has on their own controller.
 * <h2>Sticks</h2>
 * Stick positions have a {@link Gamepads#setStickDeadZone dead zone} taken out: a stick at rest reads exactly 0 instead of
 * the small drift real sticks have, and the rest of its travel is stretched to still reach 1. Directions follow the screen
 * like the rest of the engine: x grows to the right and y <em>downwards</em>, so pushing the stick up gives a negative y.
 * Triggers read 0 (released) to 1 (fully pulled).
 * <h2>What counts as game input</h2>
 * As with the keyboard, a controller used by the interface is not used by the game: while a popup or modal is open or an
 * element has keyboard focus, the D-pad, left stick, A, B and the bumpers drive the interface as the arrow keys, Enter,
 * Escape and Tab do, buttons pressed then do not show here, and the sticks read 0. A window that does not have the OS
 * focus sees an untouched pad too. Releases always come through, so nothing is left held.
 * <p>
 * Render thread only, like the rest of {@link Input}.
 */
public final class Gamepad {
    static final int BUTTON_COUNT = GLFW.GLFW_GAMEPAD_BUTTON_LAST + 1;
    static final int AXIS_COUNT = GLFW.GLFW_GAMEPAD_AXIS_LAST + 1;
    /**
     * Every axis in each direction, as {@link Binding} codes: stick axes both ways, triggers one way only.
     */
    static final int DIRECTION_COUNT = AXIS_COUNT * 2;

    /**
     * The buttons of the Xbox layout every pad is mapped to, in GLFW's order.
     */
    public enum Button {
        /** The bottom face button: Cross on PlayStation. */
        A,
        /** The right face button: Circle on PlayStation. */
        B,
        /** The left face button: Square on PlayStation. */
        X,
        /** The top face button: Triangle on PlayStation. */
        Y,
        LEFT_BUMPER, RIGHT_BUMPER,
        /** View on Xbox, Share or Create on PlayStation, minus on Switch. */
        BACK,
        /** Menu on Xbox, Options on PlayStation, plus on Switch. */
        START,
        /** The logo button. The OS often keeps it for itself, so it may never arrive. */
        GUIDE,
        /** Pressing the left stick in. */
        LEFT_THUMB,
        /** Pressing the right stick in. */
        RIGHT_THUMB,
        DPAD_UP, DPAD_RIGHT, DPAD_DOWN, DPAD_LEFT;

        private static final Button[] VALUES = values();

        /**
         * The GLFW code ({@code GLFW_GAMEPAD_BUTTON_*}), which is also the position in this enum.
         */
        public int code() {
            return ordinal();
        }

        /**
         * The button with GLFW code {@code code}, or null if there is none.
         */
        public static Button of(int code) {
            return code >= 0 && code < VALUES.length ? VALUES[code] : null;
        }

        /**
         * The name printed on the button of a controller with this layout, such as "A", "Cross" or "L1".
         */
        public String getDisplayName(Layout layout) {
            return Names.button(this, layout);
        }

        /**
         * The name in the text form of a {@link Binding}, without the {@code PAD_} prefix: {@code LEFT_BUMPER}.
         */
        String textName() {
            return name();
        }
    }

    /**
     * The six analog axes. The stick axes run from -1 to 1 and the triggers from 0 to 1.
     */
    public enum Axis {
        LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER;

        private static final Axis[] VALUES = values();

        public boolean isTrigger() {
            return this == LEFT_TRIGGER || this == RIGHT_TRIGGER;
        }

        /**
         * The GLFW code ({@code GLFW_GAMEPAD_AXIS_*}), which is also the position in this enum.
         */
        public int code() {
            return ordinal();
        }

        public static Axis of(int code) {
            return code >= 0 && code < VALUES.length ? VALUES[code] : null;
        }

        /**
         * The name of this axis pushed one way, such as "Left Stick Up" or "RT": the way the {@link Binding} for it reads.
         *
         * @param positive the right or down side of a stick, or a pulled trigger; false for the left or up side
         */
        public String getDisplayName(boolean positive, Layout layout) {
            return Names.axis(this, positive, layout);
        }
    }

    public enum Stick {
        LEFT, RIGHT
    }

    /**
     * The family a controller belongs to, which decides what its buttons are called.
     */
    public enum Layout {
        XBOX, PLAYSTATION, NINTENDO,
        /** Anything else: buttons are named as on an Xbox pad, spelled out. */
        GENERIC;

        /**
         * Guesses the layout from the name of the pad, such as "PS5 Controller" or "Xbox One Controller".
         */
        public static Layout of(String padName) {
            if (padName == null) return GENERIC;
            String n = padName.toLowerCase(Locale.ROOT);
            if (n.contains("xbox") || n.contains("xinput") || n.contains("microsoft")) return XBOX;
            if (n.contains("playstation") || n.contains("ps3") || n.contains("ps4") || n.contains("ps5")
                    || n.contains("dualshock") || n.contains("dualsense") || n.contains("sony")
                    || n.equals("wireless controller")) {
                return PLAYSTATION;
            }
            if (n.contains("nintendo") || n.contains("switch") || n.contains("joy-con") || n.contains("joycon")
                    || n.contains("pro controller")) {
                return NINTENDO;
            }
            return GENERIC;
        }
    }

    /**
     * Told when a controller is plugged in or removed, from {@link Input#addGamepadListener}. Runs on the render thread,
     * during the frame the change is noticed. A pad that was already plugged in when the window opened is announced in
     * the window's first frame.
     */
    public interface Listener {
        default void connected(Gamepad pad) {
        }

        default void disconnected(Gamepad pad) {
        }
    }

    private final Input input;
    private final int index;
    private boolean connected;
    private String name = "";
    private String guid = "";
    private Layout layout = Layout.GENERIC;

    // What the game sees
    final PressState[] buttons = new PressState[BUTTON_COUNT];
    final float[] axes = new float[AXIS_COUNT];     // processed: dead zone out, sticks -1 to 1, triggers 0 to 1
    final float[] rawAxes = new float[AXIS_COUNT];  // as the hardware reports, same ranges

    // What Input has seen of the hardware, to find the changes
    final boolean[] physical = new boolean[BUTTON_COUNT];
    final boolean[] physicalDirection = new boolean[DIRECTION_COUNT];
    final float[] pushed = new float[DIRECTION_COUNT]; // the analog value last given to the contexts, per direction
    boolean baseline; // the next live poll adopts whatever is held without pressing it

    Gamepad(Input input, int index) {
        this.input = input;
        this.index = index;
        for (int i = 0; i < buttons.length; i++) buttons[i] = new PressState();
    }

    /**
     * The slot, 0 to 15. It stays with the controller for as long as it is plugged in, and is given to the next one
     * plugged in when it is removed.
     */
    public int getIndex() {
        return index;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * What the OS calls the controller, such as "Xbox 360 Controller", or "" when nothing has been in the slot. After the
     * controller is removed it is still the name of the one that was.
     */
    public String getName() {
        return name;
    }

    /**
     * The id SDL and GLFW identify this model of controller by, which is the key of its entry in the mapping database.
     */
    public String getGuid() {
        return guid;
    }

    /**
     * The family of the controller, for naming its buttons. {@link Layout#GENERIC} when nothing has been in the slot.
     */
    public Layout getLayout() {
        return layout;
    }

    // ---------------------------------------------------------------- buttons

    /**
     * True while {@code button} is held. The D-pad counts as four buttons.
     */
    public boolean isDown(Button button) {
        return buttons[button.ordinal()].down;
    }

    /**
     * True if {@code button} was pressed since the last step (in a fixed step) or during this frame (anywhere else).
     */
    public boolean isJustPressed(Button button) {
        return buttons[button.ordinal()].justPressed(input.frame);
    }

    public boolean isJustReleased(Button button) {
        return buttons[button.ordinal()].justReleased(input.frame);
    }

    /**
     * Seconds of game time {@code button} has been held, counting the current step or frame, or 0 when it isn't held.
     */
    public float getHeldTime(Button button) {
        return buttons[button.ordinal()].heldTime();
    }

    // ---------------------------------------------------------------- axes

    /**
     * The axis with the dead zone taken out: -1 to 1 for a stick axis (y grows downwards, so up is negative) and 0 to 1 for
     * a trigger. 0 while the interface has the controller.
     */
    public float getAxis(Axis axis) {
        return axes[axis.ordinal()];
    }

    /**
     * As {@link #getAxis}, but as the hardware reports it: with the dead zone left in and whatever the interface is doing.
     * For a calibration screen, or to apply your own dead zone.
     */
    public float getRawAxis(Axis axis) {
        return rawAxes[axis.ordinal()];
    }

    /**
     * A stick as a vector in screen orientation (x right, y down), at most 1 long, with the dead zone out.
     */
    public Vec2 getStick(Stick stick) {
        int first = stick == Stick.LEFT ? GLFW.GLFW_GAMEPAD_AXIS_LEFT_X : GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X;
        float x = axes[first], y = axes[first + 1];
        return x == 0f && y == 0f ? Vec2.ZERO : new Vec2(x, y);
    }

    public Vec2 getLeftStick() {
        return getStick(Stick.LEFT);
    }

    public Vec2 getRightStick() {
        return getStick(Stick.RIGHT);
    }

    /**
     * How far the left trigger is pulled, 0 to 1.
     */
    public float getLeftTrigger() {
        return axes[GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER];
    }

    public float getRightTrigger() {
        return axes[GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER];
    }

    @Override
    public String toString() {
        return connected ? "Gamepad[" + index + ", " + name + "]" : "Gamepad[" + index + ", not connected]";
    }

    // ---------------------------------------------------------------- fed by Input

    void connect(String name, String guid) {
        this.connected = true;
        this.name = name == null ? "" : name;
        this.guid = guid == null ? "" : guid;
        this.layout = Layout.of(this.name);
    }

    void disconnect() {
        connected = false; // the name stays, for a listener saying which controller went
        Arrays.fill(axes, 0f);
        Arrays.fill(rawAxes, 0f);
        Arrays.fill(real, 0f);
    }

    private final float[] real = new float[AXIS_COUNT]; // dead zone out, whatever the interface is doing

    /**
     * Takes a hardware reading, as GLFW gives it: the raw axes, and the same with the dead zone out (a radial one for each
     * stick, a threshold for each trigger, the rest of the travel stretched over the whole range). The game's own view,
     * {@link #axes}, is set by {@link #showAxes}.
     */
    void readAxes(float[] hw) {
        for (int stick = 0; stick < 2; stick++) {
            int x = stick * 2, y = x + 1;
            rawAxes[x] = clamp(hw[x], -1f, 1f);
            rawAxes[y] = clamp(hw[y], -1f, 1f);
            float[] p = deadZone(rawAxes[x], rawAxes[y], Gamepads.getStickDeadZone());
            real[x] = p[0];
            real[y] = p[1];
        }
        float zone = Gamepads.getTriggerDeadZone();
        for (int t = GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER; t <= GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER; t++) {
            rawAxes[t] = clamp((hw[t] + 1f) / 2f, 0f, 1f); // GLFW: -1 at rest, 1 fully pulled
            real[t] = rawAxes[t] > zone ? Math.min(1f, (rawAxes[t] - zone) / (1f - zone)) : 0f;
        }
    }

    /**
     * Sets what the game reads from the last hardware reading: all of it, or nothing while the interface has the pad.
     */
    void showAxes(boolean visible) {
        for (int i = 0; i < AXIS_COUNT; i++) axes[i] = visible ? real[i] : 0f;
    }

    /**
     * The strength, 0 to 1, of the axis pushed in the direction {@code code} of a {@link Binding}, from the hardware
     * reading rather than the game's view.
     */
    float directionValue(int code) {
        float value = real[code >> 1];
        return Math.max(0f, (code & 1) != 0 ? value : -value);
    }

    static float[] deadZone(float x, float y, float zone) {
        float length = (float) Math.sqrt(x * x + y * y);
        if (length <= zone || length == 0f) return new float[]{0f, 0f};
        float scaled = Math.min(1f, (length - zone) / (1f - zone));
        return new float[]{x / length * scaled, y / length * scaled};
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    /**
     * The directions of the {@link Binding} codes that exist: the triggers have only the pulled one.
     */
    static boolean isDirection(int code) {
        if (code < 0 || code >= DIRECTION_COUNT) return false;
        return code >> 1 < GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER || (code & 1) != 0;
    }

    /**
     * Button and trigger names for each layout.
     */
    private static final class Names {
        private static final String[] XBOX = {"A", "B", "X", "Y", "LB", "RB", "View", "Menu", "Xbox", "Left Stick Click",
                "Right Stick Click", "D-Pad Up", "D-Pad Right", "D-Pad Down", "D-Pad Left"};
        private static final String[] PLAYSTATION = {"Cross", "Circle", "Square", "Triangle", "L1", "R1", "Share", "Options",
                "PS", "L3", "R3", "D-Pad Up", "D-Pad Right", "D-Pad Down", "D-Pad Left"};
        // GLFW names buttons by position; a Switch pad prints its A on the right and its B at the bottom
        private static final String[] NINTENDO = {"B", "A", "Y", "X", "L", "R", "-", "+", "Home", "Left Stick Click",
                "Right Stick Click", "D-Pad Up", "D-Pad Right", "D-Pad Down", "D-Pad Left"};
        private static final String[] GENERIC = {"A", "B", "X", "Y", "Left Bumper", "Right Bumper", "Back", "Start", "Guide",
                "Left Stick Click", "Right Stick Click", "D-Pad Up", "D-Pad Right", "D-Pad Down", "D-Pad Left"};

        static String button(Button button, Layout layout) {
            String[] table = switch (layout) {
                case XBOX -> XBOX;
                case PLAYSTATION -> PLAYSTATION;
                case NINTENDO -> NINTENDO;
                case GENERIC -> GENERIC;
            };
            return table[button.ordinal()];
        }

        static String axis(Axis axis, boolean positive, Layout layout) {
            return switch (axis) {
                case LEFT_TRIGGER -> switch (layout) {
                    case XBOX -> "LT";
                    case PLAYSTATION -> "L2";
                    case NINTENDO -> "ZL";
                    case GENERIC -> "Left Trigger";
                };
                case RIGHT_TRIGGER -> switch (layout) {
                    case XBOX -> "RT";
                    case PLAYSTATION -> "R2";
                    case NINTENDO -> "ZR";
                    case GENERIC -> "Right Trigger";
                };
                case LEFT_X -> "Left Stick " + (positive ? "Right" : "Left");
                case LEFT_Y -> "Left Stick " + (positive ? "Down" : "Up");
                case RIGHT_X -> "Right Stick " + (positive ? "Right" : "Left");
                case RIGHT_Y -> "Right Stick " + (positive ? "Down" : "Up");
            };
        }
    }
}
