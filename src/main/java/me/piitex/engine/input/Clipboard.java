package me.piitex.engine.input;

import org.lwjgl.glfw.GLFW;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges GLFW's clipboard across the engine's two threads.
 * <p>
 * GLFW documents both {@code glfwSetClipboardString} and {@code glfwGetClipboardString}
 * as main-thread-only, as with most of its windowing calls. See
 * {@link me.piitex.engine.gl.GLFWindow}'s class docs for the same constraint elsewhere
 * in this engine. All input handling, though, including a
 * {@link me.piitex.engine.ui.overlays.TextFieldOverlay}'s Ctrl/Cmd+C/X/V, runs on the
 * render thread via {@link InputProcessor#processEvents}. So instead of touching GLFW
 * directly, render-thread code calls {@link #copy}/{@link #paste} here; the actual GLFW
 * calls happen on the main thread inside {@link #pump}.
 * <p>
 * {@code glfwGetClipboardString} is called only on an explicit {@link #paste()}, never
 * unconditionally every frame. It throws GLFW_FORMAT_UNAVAILABLE, a normal and
 * recoverable outcome rather than an error, whenever the OS clipboard does not hold
 * plain text, such as when it is empty or the last copy was an image. Polling it every
 * main-loop iteration would fire that condition constantly and flood stderr through
 * GLFW's error callback.
 */
public final class Clipboard {
    private static volatile long windowHandle = 0L;

    private static final AtomicReference<String> pendingWrite = new AtomicReference<>();
    private static final AtomicReference<CompletableFuture<String>> pendingRead = new AtomicReference<>();

    private Clipboard() {
    }

    /**
     * Main thread only. Call once, right after the GLFW window is created.
     */
    public static void attach(long windowHandle) {
        Clipboard.windowHandle = windowHandle;
    }

    /**
     * Safe to call from any thread: queues {@code text} to become the OS clipboard's contents on the next {@link #pump}.
     */
    public static void copy(String text) {
        if (text != null) {
            pendingWrite.set(text);
        }
    }

    /**
     * Safe to call from any thread (typically the render thread, e.g. on Ctrl/Cmd+V):
     * reads the OS clipboard's current text. Hands the actual read off to the main
     * thread's next {@link #pump} and blocks briefly for it to complete, at most one
     * iteration of Engine's main loop, which is sub-millisecond in practice since that
     * loop spins continuously. It returns "", never null, both when the clipboard holds
     * no text and when the main thread does not service the request within 200ms, as can
     * happen while it is inside a native event such as a live window resize, rather than
     * blocking indefinitely.
     */
    public static String paste() {
        if (windowHandle == 0L) return "";

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRead.set(future);
        try {
            return future.get(200, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return "";
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * Main thread only. Flushes any pending {@link #copy} or {@link #paste} request. Call once per Engine main-loop iteration.
     */
    public static void pump() {
        if (windowHandle == 0L) return;

        String toWrite = pendingWrite.getAndSet(null);
        if (toWrite != null) {
            GLFW.glfwSetClipboardString(windowHandle, toWrite);
        }

        CompletableFuture<String> future = pendingRead.getAndSet(null);
        if (future != null) {
            String current = GLFW.glfwGetClipboardString(windowHandle);
            future.complete(current == null ? "" : current);
        }
    }
}
