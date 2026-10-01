package me.piitex.engine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The graphics API Skia draws a window with. Chosen per window with {@link WindowOptions#setRenderBackend}.
 */
public enum RenderBackend {
    /**
     * The platform's default: Metal on macOS, OpenGL elsewhere. The {@code rengl.backend} system property
     * ({@code opengl}, {@code metal} or {@code vulkan}) overrides this, which is handy for trying a backend without
     * touching the code.
     */
    AUTO,
    /**
     * OpenGL 3.3 core. Windows and Linux only; macOS windows have no OpenGL context.
     */
    OPENGL,
    /**
     * Metal. macOS only.
     */
    METAL,
    /**
     * Vulkan, through Skia's Vulkan (Ganesh) backend. Needs a Vulkan driver (on macOS the MoltenVK that ships with
     * LWJGL). If the machine has no usable Vulkan GPU the window falls back to the platform default.
     */
    VULKAN;

    private static final Logger log = LogManager.getLogger("Engine");
    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");

    /**
     * Metal on macOS, OpenGL everywhere else.
     */
    public static RenderBackend platformDefault() {
        return MAC ? METAL : OPENGL;
    }

    /**
     * Turns a request into a backend that can exist on this platform: {@link #AUTO} becomes the system property's
     * choice or the platform default, and a backend the platform can't have becomes the platform default. Whether
     * Vulkan has a usable GPU is not known here; the window checks that when it opens.
     */
    public static RenderBackend resolve(RenderBackend requested) {
        RenderBackend backend = requested == null ? AUTO : requested;
        if (backend == AUTO) {
            String property = System.getProperty("rengl.backend");
            if (property != null && !property.isBlank()) {
                try {
                    backend = valueOf(property.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown rengl.backend '{}', expected opengl, metal or vulkan.", property);
                }
            }
        }
        if (backend == AUTO) return platformDefault();
        if (backend == METAL && !MAC) {
            log.warn("Metal only exists on macOS, using {} instead.", platformDefault());
            return platformDefault();
        }
        if (backend == OPENGL && MAC) {
            log.warn("macOS windows have no OpenGL context, using {} instead.", platformDefault());
            return platformDefault();
        }
        return backend;
    }
}
