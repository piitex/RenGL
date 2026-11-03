package me.piitex.engine.input;

import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the operating system's input method (IME) across the engine's two threads, the same way {@link CursorManager}
 * does for the mouse cursor.
 * <p>
 * An input method is what lets a keyboard type Chinese, Japanese, Korean and the many other languages that have more
 * characters than keys: the user types phonetically, the input method shows what it is composing and offers
 * candidates, and the chosen text arrives as ordinary typed characters. GLFW reports the composition through a
 * callback (see {@link InputManager#onPreedit}), but only while the window has its IME mode on, and it places the
 * input method's candidate window wherever the application says the caret is.
 * <p>
 * GLFW's window calls are main-thread only, while the text field that has focus is known on the render thread, so
 * {@link #request} only records what is wanted and {@link #pump} makes the GLFW calls on the main thread.
 */
public final class ImeManager {
    private static final Map<Long, Slot> slots = new ConcurrentHashMap<>();
    private static volatile Slot selected;

    private static final class Slot {
        final long windowHandle;
        volatile boolean wantsIme;
        volatile int x, y, width, height;
        boolean appliedIme;      // main thread
        int appliedX = Integer.MIN_VALUE, appliedY, appliedWidth, appliedHeight;

        Slot(long windowHandle) {
            this.windowHandle = windowHandle;
        }
    }

    private ImeManager() {
    }

    /**
     * Main thread only. Call once per window, right after the GLFW window is created. The input method is off until a
     * text field asks for it, so a game that reads keys as controls is not handed a composition window.
     */
    public static void attach(long windowHandle) {
        Slot slot = new Slot(windowHandle);
        slots.put(windowHandle, slot);
        if (selected == null) selected = slot;
        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_IME, GLFW.GLFW_FALSE);
    }

    /**
     * Main thread only. Forgets a window that is being destroyed.
     */
    public static void detach(long windowHandle) {
        Slot slot = slots.remove(windowHandle);
        if (slot != null && selected == slot) selected = null;
    }

    /**
     * Engine use, render thread: sends {@link #request} calls to this window until the next call.
     */
    public static void select(long windowHandle) {
        Slot slot = slots.get(windowHandle);
        if (slot != null) selected = slot;
    }

    /**
     * Safe to call from any thread: says whether the window being processed wants an input method now, which is while a
     * text field has focus, and where its caret is in window coordinates so the input method's candidate list opens
     * beside it. Takes effect on the next {@link #pump}.
     */
    public static void request(boolean wantsIme, float x, float y, float width, float height) {
        Slot slot = selected;
        if (slot == null) return;
        slot.wantsIme = wantsIme;
        slot.x = Math.round(x);
        slot.y = Math.round(y);
        slot.width = Math.max(1, Math.round(width));
        slot.height = Math.max(1, Math.round(height));
    }

    /**
     * Main thread only. Applies each window's most recently requested input method state if it changed. Call once per
     * Engine main-loop iteration.
     */
    public static void pump() {
        for (Slot slot : slots.values()) {
            boolean wants = slot.wantsIme;
            if (wants != slot.appliedIme) {
                slot.appliedIme = wants;
                GLFW.glfwSetInputMode(slot.windowHandle, GLFW.GLFW_IME, wants ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
                if (!wants) GLFW.glfwResetPreeditText(slot.windowHandle); // a half-typed composition must not follow focus elsewhere
                slot.appliedX = Integer.MIN_VALUE; // the new composition needs the rectangle again
            }
            if (!wants) continue;
            int x = slot.x, y = slot.y, w = slot.width, h = slot.height;
            if (x == slot.appliedX && y == slot.appliedY && w == slot.appliedWidth && h == slot.appliedHeight) continue;
            slot.appliedX = x;
            slot.appliedY = y;
            slot.appliedWidth = w;
            slot.appliedHeight = h;
            GLFW.glfwSetPreeditCursorRectangle(slot.windowHandle, x, y, w, h);
        }
    }
}
