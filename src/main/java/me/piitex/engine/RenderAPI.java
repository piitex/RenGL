package me.piitex.engine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The graphics API Skia draws a window with. Chosen per window with {@link WindowOptions#setRenderer}.
 */
public enum RenderAPI {
    /**
     * The platform's default: Metal on macOS, OpenGL elsewhere. The {@code rengl.backend} system property
     * ({@code opengl}, {@code metal} or {@code vulkan}) overrides this, which is handy for trying a backend without
     * touching the code.
     */
    AUTO,
    /**
     * OpenGL 3.3 core. Windows and Linux only; macOS windows have no OpenGL context, and asking for OpenGL there is a
     * fatal error that tells you to use {@link #VULKAN}.
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
    public static RenderAPI platformDefault() {
        return MAC ? METAL : OPENGL;
    }

    /**
     * Turns a request into a backend that can exist on this platform: {@link #AUTO} becomes the system property's
     * choice or the platform default, and Metal off macOS becomes the platform default, with a warning. Whether
     * Vulkan has a usable GPU is not known here; the window checks that when it opens.
     *
     * @throws IllegalStateException when OpenGL is asked for on macOS, which has no OpenGL context for windows. This is
     *                               a fatal error on purpose: a game written for OpenGL would otherwise quietly run
     *                               on another API. The message says to use {@link #VULKAN}
     */
    public static RenderAPI resolve(RenderAPI requested) {
        RenderAPI backend = requested == null ? AUTO : requested;
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
            String message = "OpenGL is not available on macOS: its windows have no OpenGL context. Use RenderAPI.VULKAN "
                    + "(required for the 3D layer; windowOptions.setRenderer(RenderAPI.VULKAN)), or RenderAPI.AUTO for Metal "
                    + "(2D only). Remove -Drengl.backend=opengl if that is what asked for OpenGL.";
            log.fatal(message);
            throw new IllegalStateException(message);
        }
        return backend;
    }
}
