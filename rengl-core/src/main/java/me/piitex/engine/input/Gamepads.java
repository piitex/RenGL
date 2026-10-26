package me.piitex.engine.input;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Where controller state comes from, and the settings every {@link Gamepad} shares. GLFW only lets the main thread talk to
 * controllers, so the engine reads them there, about a hundred times a second, and each window's {@link Input} takes the
 * latest reading at the start of its frame. Nothing here needs calling for controllers to work.
 * <p>
 * Only controllers GLFW has a mapping for show up: Xbox, PlayStation and Switch pads, and the many that report as one.
 * GLFW ships the community mapping database (SDL_GameControllerDB); a pad that is missing from it, such as a niche arcade
 * stick, can be added with {@link #loadMappings}. Flight sticks and wheels have no mapping and are not seen. Rumble is not
 * possible: GLFW has no way to drive a controller's motors.
 * <p>
 * The settings are read as input is processed and may be changed from any thread.
 */
public final class Gamepads {
    private static final Logger log = LogManager.getLogger("Gamepads");

    /**
     * How many controllers GLFW tracks: slots 0 to 15.
     */
    public static final int MAX = GLFW.GLFW_JOYSTICK_LAST + 1;

    private static volatile float stickDeadZone = 0.2f;
    private static volatile float triggerDeadZone = 0.05f;
    private static volatile float pressPoint = 0.5f;
    private static volatile boolean enabled = true;

    private static final Pad[] NONE = new Pad[MAX];
    private static volatile Pad[] snapshot = NONE;
    private static Backend backend; // main thread; made on the first pump
    private static boolean failed;
    private static final ConcurrentLinkedQueue<Runnable> mainTasks = new ConcurrentLinkedQueue<>();

    private Gamepads() {
    }

    // ---------------------------------------------------------------- settings

    /**
     * How far a stick must be pushed before it counts, as a fraction of its travel: the circle around the middle inside
     * which it reads 0. Real sticks do not rest at exactly 0, so without this a pad lying on a table moves the player.
     * Past it the reading is stretched to still reach 1 at the edge. 0.2 by default.
     */
    public static void setStickDeadZone(float zone) {
        stickDeadZone = Math.max(0f, Math.min(0.9f, zone));
    }

    public static float getStickDeadZone() {
        return stickDeadZone;
    }

    /**
     * How far a trigger must be pulled before it counts. 0.05 by default.
     */
    public static void setTriggerDeadZone(float zone) {
        triggerDeadZone = Math.max(0f, Math.min(0.9f, zone));
    }

    public static float getTriggerDeadZone() {
        return triggerDeadZone;
    }

    /**
     * How far a stick or trigger must be pushed, after the dead zone, for a {@link Binding#gamepadAxis binding on it} to
     * count as pressed: "stick left" as a button. It lets go again a little below this, so a stick hovering at the edge does
     * not flicker. 0.5 by default.
     */
    public static void setPressPoint(float point) {
        pressPoint = Math.max(0.1f, Math.min(0.95f, point));
    }

    public static float getPressPoint() {
        return pressPoint;
    }

    /**
     * Where a pressed stick direction lets go again.
     */
    static float releasePoint() {
        return pressPoint * 0.7f;
    }

    /**
     * Whether the engine reads controllers at all. On by default. Turn it off for a program that never uses them; every
     * {@link Gamepad} then reads as not connected.
     */
    public static void setEnabled(boolean enabled) {
        Gamepads.enabled = enabled;
        if (!enabled) snapshot = NONE;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // ---------------------------------------------------------------- mappings

    /**
     * Adds controller mappings in SDL's text form, one per line, such as the lines of a {@code gamecontrollerdb.txt}. A
     * mapping with the GUID of a pad already known replaces it. Safe from any thread; it takes effect on the next pump of
     * the main thread, and for a pad already plugged in, once it is plugged in again.
     */
    public static void loadMappings(String text) {
        if (text == null) throw new IllegalArgumentException("mappings must not be null");
        mainTasks.add(() -> {
            if (backend == null) backend = new GlfwBackend();
            if (!backend.addMappings(text)) log.warn("GLFW did not accept the controller mappings");
        });
    }

    /**
     * As {@link #loadMappings(String)}, from a file such as the {@code gamecontrollerdb.txt} of SDL_GameControllerDB.
     */
    public static void loadMappings(Path file) throws IOException {
        loadMappings(Files.readString(file, StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- the main thread

    /**
     * Main thread, every pass of the engine's loop: reads every controller there is.
     */
    public static void pump() {
        if (!enabled || failed) return;
        try {
            if (backend == null) backend = new GlfwBackend();
            for (Runnable task; (task = mainTasks.poll()) != null; ) task.run();
            read(backend);
        } catch (Throwable t) {
            failed = true; // a broken controller backend must not take the main loop down with it
            snapshot = NONE;
            log.error("Controller input stopped", t);
        }
    }

    /**
     * Main thread, when the engine stops: lets go of the native state.
     */
    public static void shutdown() {
        if (backend != null) backend.close();
        backend = null;
        failed = false;
        snapshot = NONE;
        mainTasks.clear();
    }

    /**
     * Reads every slot of {@code source} and publishes the result. A pad whose reading did not change keeps its old
     * snapshot object, so a resting pad costs nothing to compare.
     */
    static void read(Backend source) {
        Pad[] previous = snapshot;
        Pad[] next = null;
        float[] axes = new float[Gamepad.AXIS_COUNT];
        boolean[] buttons = new boolean[Gamepad.BUTTON_COUNT];
        for (int jid = 0; jid < MAX; jid++) {
            Pad now = null;
            if (source.present(jid) && source.read(jid, axes, buttons)) {
                Pad old = previous[jid];
                if (old != null && Arrays.equals(old.axes, axes) && Arrays.equals(old.buttons, buttons)) {
                    now = old;
                } else {
                    boolean known = old != null;
                    now = new Pad(jid, known ? old.name : source.name(jid), known ? old.guid : source.guid(jid),
                            axes.clone(), buttons.clone());
                    if (!known) log.info("Controller {} connected: {} ({})", jid, now.name, now.guid);
                }
            } else if (previous[jid] != null) {
                log.info("Controller {} disconnected: {}", jid, previous[jid].name);
            }
            if (now != previous[jid]) {
                if (next == null) next = previous.clone();
                next[jid] = now;
            }
        }
        if (next != null) snapshot = next;
    }

    /**
     * The latest reading of every slot: null where nothing is plugged in. Never changes after it is returned.
     */
    static Pad[] snapshot() {
        return snapshot;
    }

    /**
     * Test seam: forgets every reading and puts the settings back to their defaults.
     */
    static void reset() {
        snapshot = NONE;
        stickDeadZone = 0.2f;
        triggerDeadZone = 0.05f;
        pressPoint = 0.5f;
        enabled = true;
    }

    /**
     * One controller as of one reading. Never changed after it is made.
     */
    static final class Pad {
        final int index;
        final String name;
        final String guid;
        final float[] axes;       // GLFW's order and ranges: triggers -1 at rest to 1
        final boolean[] buttons;

        Pad(int index, String name, String guid, float[] axes, boolean[] buttons) {
            this.index = index;
            this.name = name == null ? "" : name;
            this.guid = guid == null ? "" : guid;
            this.axes = axes;
            this.buttons = buttons;
        }
    }

    /**
     * Where readings come from.
     */
    interface Backend {
        /**
         * Whether slot {@code jid} holds a controller that can be read.
         */
        boolean present(int jid);

        /**
         * Fills {@code axes} (GLFW's six, triggers from -1 at rest to 1) and {@code buttons} (GLFW's fifteen) with the
         * state of the controller in the slot. False if it cannot be read, such as one without a mapping.
         */
        boolean read(int jid, float[] axes, boolean[] buttons);

        String name(int jid);

        String guid(int jid);

        boolean addMappings(String text);

        default void close() {
        }
    }

    /**
     * GLFW's controller functions. Main thread only.
     */
    private static final class GlfwBackend implements Backend {
        private final GLFWGamepadState state = GLFWGamepadState.malloc();
        private final boolean[] warned = new boolean[MAX];

        @Override
        public boolean present(int jid) {
            return GLFW.glfwJoystickPresent(jid);
        }

        @Override
        public boolean read(int jid, float[] axes, boolean[] buttons) {
            if (!GLFW.glfwJoystickIsGamepad(jid)) {
                if (!warned[jid]) {
                    warned[jid] = true;
                    log.info("Joystick {} ({}) has no gamepad mapping, so it is ignored; Gamepads.loadMappings can add one",
                            jid, GLFW.glfwGetJoystickName(jid));
                }
                return false;
            }
            warned[jid] = false;
            if (!GLFW.glfwGetGamepadState(jid, state)) return false;
            for (int i = 0; i < axes.length; i++) axes[i] = state.axes(i);
            for (int i = 0; i < buttons.length; i++) buttons[i] = state.buttons(i) == GLFW.GLFW_PRESS;
            return true;
        }

        @Override
        public String name(int jid) {
            String name = GLFW.glfwGetGamepadName(jid);
            return name != null ? name : GLFW.glfwGetJoystickName(jid);
        }

        @Override
        public String guid(int jid) {
            return GLFW.glfwGetJoystickGUID(jid);
        }

        @Override
        public boolean addMappings(String text) {
            ByteBuffer utf8 = MemoryUtil.memUTF8(text); // null-terminated, as GLFW needs
            try {
                return GLFW.glfwUpdateGamepadMappings(utf8);
            } finally {
                MemoryUtil.memFree(utf8);
            }
        }

        @Override
        public void close() {
            state.free();
        }
    }
}
