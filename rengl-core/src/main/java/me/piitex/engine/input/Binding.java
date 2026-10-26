package me.piitex.engine.input;

import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * One key, mouse button, wheel direction, controller button or controller stick direction that can trigger an action in
 * an {@link InputContext}, optionally with modifier keys held: {@code Binding.key(GLFW_KEY_S, GLFW_MOD_CONTROL)} is Ctrl+S.
 * <p>
 * Keys are GLFW key codes, which name physical keys by where they are on a US keyboard: {@code GLFW_KEY_W} is the
 * key above S on any layout, "Z" on a French one. {@link #getDisplayName()} gives the name printed on the player's
 * own keyboard.
 * <p>
 * {@link #toString()} gives a stable text form, such as {@code SPACE}, {@code CTRL+S}, {@code MOUSE_LEFT} or
 * {@code WHEEL_UP}, which {@link #parse} reads back. {@link InputContext#save} stores bindings this way.
 * <h2>Controllers</h2>
 * {@link #gamepad(Gamepad.Button)} is a button of a {@link Gamepad} ({@code PAD_A}, {@code PAD_DPAD_UP}, {@code PAD_START}),
 * and {@link #gamepadAxis(Gamepad.Axis, boolean)} is a stick pushed one way or a trigger pulled ({@code PAD_LEFT_STICK_LEFT},
 * {@code PAD_RIGHT_TRIGGER}). Both work with any controller, are named by the {@link Gamepad.Layout layout} of the one the
 * player has ({@link #getDisplayName(Gamepad.Layout)}), and cannot have keyboard modifiers. A stick binding is analog: the
 * action's {@link InputContext#getValue value} is how far the stick is pushed, and it counts as pressed past
 * {@link Gamepads#setPressPoint a press point}.
 *
 * @param device    what the binding reads
 * @param code      a GLFW key code, a GLFW mouse button (0 to 7), a wheel direction ({@link #WHEEL_UP} and so on), a
 *                  {@link Gamepad.Button#code() controller button}, or a controller axis direction: twice the
 *                  {@link Gamepad.Axis#code() axis}, plus one for the right or down side of a stick (a trigger has only
 *                  that side)
 * @param modifiers GLFW modifier bits ({@code GLFW_MOD_SHIFT}, {@code _CONTROL}, {@code _ALT}, {@code _SUPER}) that
 *                  must be held, or 0 for none; always 0 for the controller devices
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public record Binding(Device device, int code, int modifiers) {

    public enum Device {
        KEY, MOUSE, WHEEL, GAMEPAD_BUTTON, GAMEPAD_AXIS
    }

    /**
     * The sources of controller inputs are told apart by pad, as {@code PAD_SOURCE + pad * PAD_SPAN + offset}, so two pads
     * holding the same button are two inputs. Axes sit past the buttons in each pad's span.
     */
    private static final int PAD_SOURCE = 10_000;
    private static final int PAD_SPAN = 64;
    private static final int PAD_AXIS_OFFSET = 32;

    public static final int WHEEL_UP = 0;
    public static final int WHEEL_DOWN = 1;
    public static final int WHEEL_LEFT = 2;
    public static final int WHEEL_RIGHT = 3;

    /**
     * Every modifier bit a binding can require. Caps Lock and Num Lock are not modifiers here.
     */
    public static final int MODIFIER_MASK = GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT
            | GLFW.GLFW_MOD_SUPER;

    private static final int[] MODIFIER_ORDER = {GLFW.GLFW_MOD_SHIFT, GLFW.GLFW_MOD_CONTROL, GLFW.GLFW_MOD_ALT,
            GLFW.GLFW_MOD_SUPER};
    private static final String[] MODIFIER_NAMES = {"SHIFT", "CTRL", "ALT", "SUPER"};
    private static final String[] WHEEL_NAMES = {"WHEEL_UP", "WHEEL_DOWN", "WHEEL_LEFT", "WHEEL_RIGHT"};
    private static final String[] WHEEL_DISPLAY = {"Wheel Up", "Wheel Down", "Wheel Left", "Wheel Right"};
    private static final String PAD_PREFIX = "PAD_";
    // By axis direction code: twice the axis, plus one for the right or down side. A trigger has only the pulled side.
    private static final String[] AXIS_NAMES = {"LEFT_STICK_LEFT", "LEFT_STICK_RIGHT", "LEFT_STICK_UP", "LEFT_STICK_DOWN",
            "RIGHT_STICK_LEFT", "RIGHT_STICK_RIGHT", "RIGHT_STICK_UP", "RIGHT_STICK_DOWN", null, "LEFT_TRIGGER", null,
            "RIGHT_TRIGGER"};

    public Binding {
        if (device == null) throw new IllegalArgumentException("device must not be null");
        switch (device) {
            case KEY -> {
                if (KeyNames.name(code) == null) throw new IllegalArgumentException("not a GLFW key: " + code);
            }
            case MOUSE -> {
                if (code < 0 || code > GLFW.GLFW_MOUSE_BUTTON_LAST) {
                    throw new IllegalArgumentException("mouse button must be 0 to " + GLFW.GLFW_MOUSE_BUTTON_LAST + ": " + code);
                }
            }
            case WHEEL -> {
                if (code < WHEEL_UP || code > WHEEL_RIGHT) throw new IllegalArgumentException("not a wheel direction: " + code);
            }
            case GAMEPAD_BUTTON -> {
                if (Gamepad.Button.of(code) == null) throw new IllegalArgumentException("not a controller button: " + code);
            }
            case GAMEPAD_AXIS -> {
                if (!Gamepad.isDirection(code)) {
                    throw new IllegalArgumentException("not a controller axis direction (a trigger has only the pulled one): " + code);
                }
            }
        }
        if ((modifiers & ~MODIFIER_MASK) != 0) {
            throw new IllegalArgumentException("modifiers must be GLFW_MOD_SHIFT, _CONTROL, _ALT or _SUPER bits: " + modifiers);
        }
        if (modifiers != 0 && (device == Device.GAMEPAD_BUTTON || device == Device.GAMEPAD_AXIS)) {
            throw new IllegalArgumentException("a controller binding cannot have keyboard modifiers: " + modifiers);
        }
    }

    /**
     * A key, such as {@code GLFW_KEY_SPACE}. It triggers whatever modifiers are held, so a player holding Shift to run
     * can still jump.
     */
    public static Binding key(int key) {
        return new Binding(Device.KEY, key, 0);
    }

    /**
     * A key pressed while {@code modifiers} are held, such as {@code key(GLFW_KEY_S, GLFW_MOD_CONTROL)}.
     */
    public static Binding key(int key, int modifiers) {
        return new Binding(Device.KEY, key, modifiers);
    }

    /**
     * A mouse button, such as {@code GLFW_MOUSE_BUTTON_LEFT}.
     */
    public static Binding mouse(int button) {
        return new Binding(Device.MOUSE, button, 0);
    }

    /**
     * A mouse button pressed while {@code modifiers} are held.
     */
    public static Binding mouse(int button, int modifiers) {
        return new Binding(Device.MOUSE, button, modifiers);
    }

    /**
     * A wheel direction: {@link #WHEEL_UP}, {@link #WHEEL_DOWN}, {@link #WHEEL_LEFT} or {@link #WHEEL_RIGHT}. Each notch
     * presses and releases the action at once, so it shows as {@link InputContext#isJustPressed just pressed} and is
     * never held.
     */
    public static Binding wheel(int direction) {
        return new Binding(Device.WHEEL, direction, 0);
    }

    /**
     * A wheel direction while {@code modifiers} are held, such as Ctrl+wheel to zoom.
     */
    public static Binding wheel(int direction, int modifiers) {
        return new Binding(Device.WHEEL, direction, modifiers);
    }

    /**
     * A button of a controller, such as {@code Binding.gamepad(Gamepad.Button.A)}. Any connected controller can press it;
     * {@link InputContext#setGamepad} limits a context to one.
     */
    public static Binding gamepad(Gamepad.Button button) {
        if (button == null) throw new IllegalArgumentException("button must not be null");
        return new Binding(Device.GAMEPAD_BUTTON, button.code(), 0);
    }

    /**
     * A stick pushed one way, or a trigger pulled: {@code gamepadAxis(Gamepad.Axis.LEFT_X, false)} is the left stick pushed
     * left. As an action's binding it is analog (see {@link InputContext#getValue}).
     *
     * @param positive the right side of a horizontal stick axis, the down side of a vertical one, or a pulled trigger; false
     *                 for the left or up side. Must be true for a trigger.
     */
    public static Binding gamepadAxis(Gamepad.Axis axis, boolean positive) {
        if (axis == null) throw new IllegalArgumentException("axis must not be null");
        return new Binding(Device.GAMEPAD_AXIS, axis.code() * 2 + (positive ? 1 : 0), 0);
    }

    /**
     * A trigger pulled: the same as {@code gamepadAxis(trigger, true)}.
     */
    public static Binding gamepadTrigger(Gamepad.Axis trigger) {
        return gamepadAxis(trigger, true);
    }

    /**
     * Whether this reads a controller rather than the keyboard or mouse.
     */
    public boolean isGamepad() {
        return device == Device.GAMEPAD_BUTTON || device == Device.GAMEPAD_AXIS;
    }

    /**
     * The button this is a binding for, or null if it is not a controller button.
     */
    public Gamepad.Button gamepadButton() {
        return device == Device.GAMEPAD_BUTTON ? Gamepad.Button.of(code) : null;
    }

    /**
     * The axis this is a binding for, or null if it is not a controller axis direction.
     */
    public Gamepad.Axis gamepadAxis() {
        return device == Device.GAMEPAD_AXIS ? Gamepad.Axis.of(code >> 1) : null;
    }

    /**
     * For a controller axis binding: whether it is the right or down side of a stick, or a trigger.
     */
    public boolean isPositiveDirection() {
        return device == Device.GAMEPAD_AXIS && (code & 1) != 0;
    }

    /**
     * This binding with {@code modifiers} instead of its own.
     */
    public Binding withModifiers(int modifiers) {
        return new Binding(device, code, modifiers);
    }

    /**
     * Whether {@code key} is Shift, Control, Alt or Super, on either side.
     */
    public static boolean isModifierKey(int key) {
        return key >= GLFW.GLFW_KEY_LEFT_SHIFT && key <= GLFW.GLFW_KEY_RIGHT_SUPER;
    }

    /**
     * The name to show a player, such as "Space", "Ctrl+S", "Left Mouse" or "Wheel Up". Keys that print a character
     * are named as on the player's keyboard layout, and modifiers as on their platform ("Cmd" on macOS).
     */
    public String getDisplayName() {
        return getDisplayName(Gamepad.Layout.XBOX);
    }

    /**
     * As {@link #getDisplayName()}, naming controller inputs as on a controller with this layout, such as "Cross" or "R2" for
     * a PlayStation pad. Keys and mouse inputs do not depend on it.
     */
    public String getDisplayName(Gamepad.Layout layout) {
        StringBuilder sb = new StringBuilder();
        for (int mod : MODIFIER_ORDER) {
            if ((modifiers & mod) != 0) sb.append(KeyNames.modifier(mod)).append('+');
        }
        sb.append(switch (device) {
            case KEY -> KeyNames.display(code);
            case MOUSE -> switch (code) {
                case GLFW.GLFW_MOUSE_BUTTON_LEFT -> "Left Mouse";
                case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> "Right Mouse";
                case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "Middle Mouse";
                default -> "Mouse " + (code + 1);
            };
            case WHEEL -> WHEEL_DISPLAY[code];
            case GAMEPAD_BUTTON -> Gamepad.Button.of(code).getDisplayName(layout);
            case GAMEPAD_AXIS -> Gamepad.Axis.of(code >> 1).getDisplayName((code & 1) != 0, layout);
        });
        return sb.toString();
    }

    /**
     * The text form: modifiers first, then the key, button or direction, joined with {@code +}. For example
     * {@code SPACE}, {@code CTRL+SHIFT+Z}, {@code MOUSE_LEFT}, {@code MOUSE_4}, {@code ALT+WHEEL_UP}. A controller input
     * starts with {@code PAD_}: {@code PAD_A}, {@code PAD_DPAD_UP}, {@code PAD_LEFT_BUMPER}, {@code PAD_LEFT_STICK_UP},
     * {@code PAD_RIGHT_TRIGGER}.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MODIFIER_ORDER.length; i++) {
            if ((modifiers & MODIFIER_ORDER[i]) != 0) sb.append(MODIFIER_NAMES[i]).append('+');
        }
        sb.append(switch (device) {
            case KEY -> KeyNames.name(code);
            case MOUSE -> switch (code) {
                case GLFW.GLFW_MOUSE_BUTTON_LEFT -> "MOUSE_LEFT";
                case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> "MOUSE_RIGHT";
                case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "MOUSE_MIDDLE";
                default -> "MOUSE_" + (code + 1);
            };
            case WHEEL -> WHEEL_NAMES[code];
            case GAMEPAD_BUTTON -> PAD_PREFIX + Gamepad.Button.of(code).textName();
            case GAMEPAD_AXIS -> PAD_PREFIX + AXIS_NAMES[code];
        });
        return sb.toString();
    }

    /**
     * Reads the {@link #toString() text form}, ignoring case and spaces. Also accepts {@code CONTROL}, {@code CMD},
     * {@code META} and {@code OPTION} for the modifiers, and {@code MOUSE_1} to {@code MOUSE_8}.
     *
     * @throws IllegalArgumentException if {@code text} is not a binding
     */
    public static Binding parse(String text) {
        if (text == null) throw new IllegalArgumentException("binding text must not be null");
        String[] parts = text.replace(" ", "").toUpperCase(Locale.ROOT).split("\\+", -1);
        int mods = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            int mod = switch (parts[i]) {
                case "SHIFT" -> GLFW.GLFW_MOD_SHIFT;
                case "CTRL", "CONTROL" -> GLFW.GLFW_MOD_CONTROL;
                case "ALT", "OPTION" -> GLFW.GLFW_MOD_ALT;
                case "SUPER", "CMD", "META" -> GLFW.GLFW_MOD_SUPER;
                default -> throw new IllegalArgumentException("not a modifier: \"" + parts[i] + "\" in \"" + text + "\"");
            };
            mods |= mod;
        }
        String last = parts[parts.length - 1];
        if (last.isEmpty()) {
            // "CTRL++"-style text can't come from toString, and a lone "+" key has no name of its own.
            throw new IllegalArgumentException("not a binding: \"" + text + "\"");
        }
        if (last.startsWith(PAD_PREFIX)) {
            String pad = last.substring(PAD_PREFIX.length());
            for (Gamepad.Button button : Gamepad.Button.values()) {
                if (button.textName().equals(pad)) return new Binding(Device.GAMEPAD_BUTTON, button.code(), mods);
            }
            for (int i = 0; i < AXIS_NAMES.length; i++) {
                if (pad.equals(AXIS_NAMES[i])) return new Binding(Device.GAMEPAD_AXIS, i, mods);
            }
            throw new IllegalArgumentException("not a controller input: \"" + last + "\" in \"" + text + "\"");
        }
        switch (last) {
            case "MOUSE_LEFT" -> {
                return mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT, mods);
            }
            case "MOUSE_RIGHT" -> {
                return mouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT, mods);
            }
            case "MOUSE_MIDDLE" -> {
                return mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, mods);
            }
        }
        for (int i = 0; i < WHEEL_NAMES.length; i++) {
            if (WHEEL_NAMES[i].equals(last)) return wheel(i, mods);
        }
        if (last.startsWith("MOUSE_")) {
            try {
                int button = Integer.parseInt(last.substring(6)) - 1;
                if (button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST) return mouse(button, mods);
            } catch (NumberFormatException ignored) {
                // falls through to the error below
            }
            throw new IllegalArgumentException("not a mouse button: \"" + last + "\" in \"" + text + "\"");
        }
        int key = KeyNames.code(last);
        if (key < 0) throw new IllegalArgumentException("not a key: \"" + last + "\" in \"" + text + "\"");
        return key(key, mods);
    }

    /**
     * Key bindings are identified by key code, mouse ones offset past every key, so each physical input has one id.
     */
    int source() {
        return source(device, code);
    }

    static int source(Device device, int code) {
        return switch (device) {
            case KEY -> code;
            case MOUSE -> 1000 + code;
            case WHEEL -> 2000 + code;
            case GAMEPAD_BUTTON, GAMEPAD_AXIS -> throw new IllegalArgumentException("a controller input needs its pad: use padSource");
        };
    }

    /**
     * The source of a controller input on pad number {@code pad}, so the same button on two pads is two sources.
     */
    static int padSource(int pad, Device device, int code) {
        return PAD_SOURCE + pad * PAD_SPAN + (device == Device.GAMEPAD_AXIS ? PAD_AXIS_OFFSET : 0) + code;
    }

    /**
     * Whether a source from {@link #padSource} is a stick or trigger direction rather than a button.
     */
    static boolean isAxisSource(int source) {
        return source >= PAD_SOURCE && (source - PAD_SOURCE) % PAD_SPAN >= PAD_AXIS_OFFSET;
    }
}
