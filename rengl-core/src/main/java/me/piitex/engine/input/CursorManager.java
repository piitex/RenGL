package me.piitex.engine.input;

import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.image.ImageLoader;
import me.piitex.engine.ui.text.Font;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Bridges the OS mouse cursor across the engine's two threads, the same way {@link
 * Clipboard} bridges the OS clipboard.
 * <p>
 * GLFW documents {@code glfwCreateStandardCursor}/{@code glfwSetCursor} as main-thread-
 * only, as with most of its windowing calls. See
 * {@link me.piitex.engine.gl.GLFWindow}'s class docs for the same constraint elsewhere
 * in this engine. Hover detection, which drives cursor changes (see
 * {@link InputProcessor#handleMouseMove}), runs on the render thread instead, so
 * {@link #request} only records the desired {@link CursorMode} and the actual GLFW calls
 * happen in {@link #pump} on the main thread.
 * <p>
 * Standard cursor handles are created once per shape and reused for the process's whole
 * lifetime. Creating one is cheap, but re-creating it every time hover changes would not
 * be. The set is bounded by {@link CursorMode}'s small fixed list of values, so this
 * makes the same "never explicitly closed" trade-off {@link Font} and
 * {@link ImageLoader} make.
 */
public final class CursorManager {
    private static final Map<CursorMode, Long> cursorHandles = new EnumMap<>(CursorMode.class);

    // Each window's cursor. request() goes to the selected one, the window the engine is processing.
    private static final Map<Long, Slot> slots = new ConcurrentHashMap<>();
    private static volatile Slot selected;

    private static final class Slot {
        final long windowHandle;
        volatile CursorMode pendingMode = CursorMode.DEFAULT;
        CursorMode lastAppliedMode = null; // main thread; null forces the very first pump() to actually apply DEFAULT

        Slot(long windowHandle) {
            this.windowHandle = windowHandle;
        }
    }

    private CursorManager() {
    }

    /**
     * Main thread only. Call once per window, right after the GLFW window is created. The first window attached is
     * selected until {@link #select} says otherwise.
     */
    public static void attach(long windowHandle) {
        Slot slot = new Slot(windowHandle);
        slots.put(windowHandle, slot);
        if (selected == null) selected = slot;
    }

    /**
     * Main thread only. Forgets a window that is being destroyed.
     */
    public static void detach(long windowHandle) {
        Slot slot = slots.remove(windowHandle);
        if (slot != null && selected == slot) selected = null;
    }

    /**
     * Engine use, render thread: sends {@link #request} calls to this window's cursor until the next call.
     */
    public static void select(long windowHandle) {
        Slot slot = slots.get(windowHandle);
        if (slot != null) selected = slot;
    }

    /**
     * Safe to call from any thread (typically the render thread, from hover detection): requests the OS cursor over the window being processed become {@code mode} on the next {@link #pump}. Null is treated as {@link CursorMode#DEFAULT}.
     */
    public static void request(CursorMode mode) {
        Slot slot = selected;
        if (slot != null) slot.pendingMode = mode == null ? CursorMode.DEFAULT : mode;
    }

    /**
     * Main thread only. Applies each window's most recently requested cursor if it changed. Call once per Engine main-loop iteration.
     */
    public static void pump() {
        for (Slot slot : slots.values()) {
            CursorMode mode = slot.pendingMode;
            if (mode == slot.lastAppliedMode) continue;
            slot.lastAppliedMode = mode;

            if (mode == CursorMode.DEFAULT) {
                GLFW.glfwSetCursor(slot.windowHandle, NULL); // NULL restores the platform's own default cursor
                continue;
            }

            long handle = cursorHandles.computeIfAbsent(mode, CursorManager::createStandardCursor);
            GLFW.glfwSetCursor(slot.windowHandle, handle);
        }
    }

    private static long createStandardCursor(CursorMode mode) {
        int shape = switch (mode) {
            case TEXT -> GLFW.GLFW_IBEAM_CURSOR;
            case POINTER -> GLFW.GLFW_POINTING_HAND_CURSOR;
            case CROSSHAIR -> GLFW.GLFW_CROSSHAIR_CURSOR;
            case RESIZE_HORIZONTAL -> GLFW.GLFW_RESIZE_EW_CURSOR;
            case RESIZE_VERTICAL -> GLFW.GLFW_RESIZE_NS_CURSOR;
            case RESIZE_NWSE -> GLFW.GLFW_RESIZE_NWSE_CURSOR;
            case RESIZE_NESW -> GLFW.GLFW_RESIZE_NESW_CURSOR;
            case RESIZE_ALL -> GLFW.GLFW_RESIZE_ALL_CURSOR;
            case NOT_ALLOWED -> GLFW.GLFW_NOT_ALLOWED_CURSOR;
            default -> GLFW.GLFW_ARROW_CURSOR;
        };
        return GLFW.glfwCreateStandardCursor(shape);
    }
}
