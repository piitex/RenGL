package me.piitex.engine.gl.renderer;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.gl.renderer.vulkan.VulkanContext;
import org.jetbrains.skia.DirectContext;

/**
 * Implemented by the renderers that draw through a GPU API a separate GPU layer can share: the OpenGL and Vulkan
 * ones. It gives code that renders with the GPU itself, such as the optional 3D layer's viewport, what it
 * needs to hand its pictures to Skia. A renderer that does not implement it, the Metal one and the CPU one behind
 * {@link Snapshot}, has no GPU to share, and such code skips its drawing there.
 * <p>
 * Render thread only, like the renderer itself.
 */
public interface GpuInterop {
    /**
     * {@link RenderAPI#OPENGL} or {@link RenderAPI#VULKAN}.
     */
    RenderAPI gpuApi();

    /**
     * Skia's GPU context for this window, which is what wraps GPU images as Skia images.
     */
    DirectContext skiaContext();

    /**
     * The window's Vulkan device, queue and instance for {@link RenderAPI#VULKAN}, and null for OpenGL.
     */
    VulkanContext vulkanContext();

    /**
     * Framebuffer pixels per logical point: 2 on a retina display. A picture drawn at {@code width * pixelScale()}
     * wide is sharp.
     */
    float pixelScale();

    /**
     * The number of the window's frame being drawn, up by one for every frame. Two draws with the same number are in
     * the same frame.
     */
    long frameNumber();

    /**
     * Runs {@code cleanup} when the renderer is disposed, first, while the GPU context is still usable and before
     * Skia's is closed, so a GPU layer can free what it made. Runs once; a renderer that is already gone runs it
     * right away.
     */
    void onDispose(Runnable cleanup);

    /**
     * Runs {@code task} once the current frame (or, between frames, the next one) has been drawn and submitted, with the
     * GPU context usable. For work that must not happen in the middle of a frame, such as freeing GPU objects a frame
     * may still be using. May be called from any thread; the task runs on the render thread. Runs it right away when the
     * renderer is already gone.
     */
    void afterFrame(Runnable task);
}
