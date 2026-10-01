package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;

import java.nio.ByteBuffer;

/**
 * The 3D layer's view of the GPU. {@link me.piitex.engine.render3d.gl.GlDevice} and
 * {@link me.piitex.engine.render3d.vk.VkGpuDevice} implement it.
 * <p>
 * Threading: everything runs on the thread that owns the window's graphics context (the render thread). A frame is
 * bracketed by {@link #beginFrame()} and {@link #endFrame()}; passes are recorded in between.
 */
public interface GpuDevice extends AutoCloseable {
    /**
     * {@link RenderAPI#OPENGL} or {@link RenderAPI#VULKAN}.
     */
    RenderAPI api();

    /**
     * The GPU's name, for logs.
     */
    String name();

    /**
     * The number of the window's frame being drawn, the same for every viewport drawn in that frame and up by one for
     * the next. {@link Viewport3D.Content}s that must do something once per frame, however many viewports show them,
     * compare it with the last one they saw.
     */
    long frameNumber();

    void beginFrame();

    void endFrame();

    /**
     * A buffer with fixed contents, copied from the data's position to its limit.
     */
    GpuBuffer createBuffer(BufferType type, ByteBuffer data);

    /**
     * A buffer whose contents are replaced every frame with {@link GpuBuffer#update}.
     */
    GpuBuffer createDynamicBuffer(BufferType type, long size);

    /**
     * A texture without mipmaps. See {@link #createTexture(int, int, ByteBuffer, boolean)}.
     */
    default GpuTexture createTexture(int width, int height, ByteBuffer rgba) {
        return createTexture(width, height, rgba, false);
    }

    /**
     * @param rgba    tightly packed RGBA8, top row first, {@code width * height * 4} bytes
     * @param mipmaps also make the smaller versions of the picture and filter between them, so a texture seen from far
     *                away doesn't shimmer. Costs a third more memory and a little time at creation
     */
    GpuTexture createTexture(int width, int height, ByteBuffer rgba, boolean mipmaps);

    Pipeline createPipeline(PipelineDesc desc);

    /**
     * A target without multisampling. See {@link #createRenderTarget(int, int, boolean, int)}.
     */
    default RenderTarget createRenderTarget(int width, int height, boolean depth) {
        return createRenderTarget(width, height, depth, 1);
    }

    /**
     * @param samples anti-aliasing samples per pixel: 1 for none, or 2, 4 or 8. The GPU's limit ({@link #maxSamples()})
     *                applies, and any other number is rounded down to a power of two. A pass draws into the
     *                multisampled image and the result is resolved to an ordinary image when the pass ends, so
     *                everything that reads the target, the UI layer included, sees one sample per pixel
     */
    RenderTarget createRenderTarget(int width, int height, boolean depth, int samples);

    /**
     * The most samples a render target can have on this GPU: at least 1, at most 8.
     */
    int maxSamples();

    /**
     * Starts drawing into the target, clearing it to the colour (and the depth buffer to the far plane).
     */
    RenderPass beginPass(RenderTarget target, float r, float g, float b, float a);

    /**
     * Waits for the GPU and frees the device's own objects. Free the buffers, textures, pipelines and targets first.
     */
    @Override
    void close();
}
