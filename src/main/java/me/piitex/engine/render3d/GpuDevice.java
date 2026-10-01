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
     * @param rgba tightly packed RGBA8, top row first, {@code width * height * 4} bytes
     */
    GpuTexture createTexture(int width, int height, ByteBuffer rgba);

    Pipeline createPipeline(PipelineDesc desc);

    RenderTarget createRenderTarget(int width, int height, boolean depth);

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
