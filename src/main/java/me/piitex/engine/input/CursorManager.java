package me.piitex.engine.input;

import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.image.ImageLoader;
import me.piitex.engine.ui.text.Font;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;

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
    private static volatile long windowHandle = 0L;
    private static final Map<CursorMode, Long> cursorHandles = new EnumMap<>(CursorMode.class);

    private static volatile CursorMode pendingMode = CursorMode.DEFAULT;
    private static CursorMode lastAppliedMode = null; // null forces the very first pump() to actually apply DEFAULT

    private CursorManager() {}

    /** Main thread only. Call once, right after the GLFW window is created. */
    public static void attach(long windowHandle) {
        CursorManager.windowHandle = windowHandle;
    }

    /** Safe to call from any thread (typically the render thread, from hover detection): requests the OS cursor become {@code mode} on the next {@link #pump}. Null is treated as {@link CursorMode#DEFAULT}. */
    public static void request(CursorMode mode) {
        pendingMode = mode == null ? CursorMode.DEFAULT : mode;
    }

    /** Main thread only. Applies the most recently requested cursor if it changed. Call once per Engine main-loop iteration. */
    public static void pump() {
        if (windowHandle == 0L) return;

        CursorMode mode = pendingMode;
        if (mode == lastAppliedMode) return;
        lastAppliedMode = mode;

        if (mode == CursorMode.DEFAULT) {
            GLFW.glfwSetCursor(windowHandle, NULL); // NULL restores the platform's own default cursor
            return;
        }

        long handle = cursorHandles.computeIfAbsent(mode, CursorManager::createStandardCursor);
        GLFW.glfwSetCursor(windowHandle, handle);
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
