package me.piitex.engine.gl.renderer;

import me.piitex.engine.RenderAPI;

import org.apache.logging.log4j.LogManager;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * A graphics backend that lives in its own module, so a program that never uses it does not carry its libraries.
 * Vulkan is the one today: {@code rengl-vulkan} provides a {@code RenderBackend} for {@link RenderAPI#VULKAN}, found
 * through {@link ServiceLoader} when a window asks for it. OpenGL and Metal are part of the core and don't go through
 * this.
 * <p>
 * When a window asks for an API whose module is not on the classpath, the engine warns and uses the platform default,
 * the same as when the machine has no usable GPU for it.
 * <p>
 * To add a backend, implement this interface and list the class in
 * {@code META-INF/services/me.piitex.engine.gl.renderer.RenderBackend}.
 */
public interface RenderBackend {
    /**
     * The API this backend draws with. A backend serves exactly one.
     */
    RenderAPI api();

    /**
     * Runs on the main thread before GLFW is initialized, when a window asked for this API. For setup GLFW needs to
     * see first, such as which Vulkan loader it should use. Must not throw: a failure here only means the backend
     * will report itself unsupported.
     */
    default void beforeGlfwInit() {
    }

    /**
     * Why this backend cannot run on this machine, or null when it can. The reason completes the sentence "... was
     * requested, but ..." in the warning the engine logs before it falls back to the platform default. Called on the
     * main thread, after GLFW is initialized.
     */
    String unsupportedReason();

    /**
     * Creates the backend's state for a window: its instance, device and surface. Main thread. The window was created
     * without a client API.
     *
     * @param windowHandle the GLFW window
     * @throws RuntimeException if the device cannot be set up. The engine reports it and the window does not open
     */
    Context createContext(long windowHandle);

    /**
     * What a backend keeps for one window. Closed on the main thread after the renderer is gone and before the window
     * is destroyed.
     */
    interface Context extends AutoCloseable {
        /**
         * The name of the GPU the window draws on.
         */
        String gpuName();

        /**
         * Turns waiting for the display on or off. Render thread.
         */
        void setVsync(boolean vsync);

        /**
         * Creates the window's renderer, ready to draw at this size. Render thread, once, before the first frame.
         */
        BackendRenderer createRenderer(int logicalWidth, int logicalHeight);

        @Override
        void close();
    }

    /**
     * The renderer a backend gives a window. The engine draws through it like any {@link Renderer} and tells it about
     * resizes and when to release its GPU resources.
     */
    interface BackendRenderer extends Renderer {
        /**
         * The window's logical (point) size changed. Render thread.
         */
        void onResize(int logicalWidth, int logicalHeight);

        /**
         * Frees the renderer's GPU resources. Render thread, once, when the window closes.
         */
        void dispose();
    }

    /**
     * The backend that serves {@code api}, or null when no module on the classpath provides one.
     */
    static RenderBackend find(RenderAPI api) {
        return Registry.BACKENDS.get(api);
    }

    /**
     * Every backend found on the classpath, looked up once.
     */
    final class Registry {
        private static final Map<RenderAPI, RenderBackend> BACKENDS = new EnumMap<>(RenderAPI.class);

        static {
            Iterator<RenderBackend> found = ServiceLoader.load(RenderBackend.class, RenderBackend.class.getClassLoader()).iterator();
            while (true) {
                try {
                    if (!found.hasNext()) break;
                    RenderBackend backend = found.next();
                    BACKENDS.putIfAbsent(backend.api(), backend);
                } catch (ServiceConfigurationError | LinkageError e) {
                    // A backend whose own libraries are missing is the same as no backend: the window falls back.
                    LogManager.getLogger("Engine").warn("Skipping a render backend that could not be loaded: {}", e.toString());
                }
            }
        }

        private Registry() {
        }
    }
}
