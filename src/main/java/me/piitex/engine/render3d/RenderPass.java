package me.piitex.engine.render3d;

/**
 * Draw commands into one {@link RenderTarget}. Made by {@link GpuDevice#beginPass}; the target is cleared when the pass
 * begins and the work is handed to the GPU by {@link #end()}.
 * <p>
 * Shaders see uniform buffers and textures by the {@code binding} number in their GLSL. The projection a shader
 * applies is Vulkan-style: +Y up in clip space, depth from 0 (near) to 1 (far), on both backends.
 */
public interface RenderPass extends AutoCloseable {
    void bindPipeline(Pipeline pipeline);

    void bindVertexBuffer(GpuBuffer buffer);

    /**
     * Indices are 32-bit unsigned integers.
     */
    void bindIndexBuffer(GpuBuffer buffer);

    void bindUniformBuffer(int binding, GpuBuffer buffer);

    void bindTexture(int binding, GpuTexture texture);

    void draw(int vertexCount);

    void drawIndexed(int indexCount);

    /**
     * Finishes the pass and submits it. The target is ready for the UI layer afterwards, and left in the layout the
     * Skia wrapper expects.
     */
    void end();

    @Override
    default void close() {
        end();
    }
}
