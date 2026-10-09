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

    /**
     * Limits the draws that follow to a rectangle of the target: the clip-space picture is fitted into it and nothing is
     * drawn outside it. Used to draw several pictures into one target, like the tiles of a shadow atlas. The rectangle is
     * in pixels measured from the top-left of the picture on both backends; a pass starts with the whole target.
     * The target's clear happens when the pass begins and always covers all of it.
     */
    void setViewport(int x, int y, int width, int height);

    /**
     * Binds the pipeline's main vertex buffer, buffer binding 0.
     */
    default void bindVertexBuffer(GpuBuffer buffer) {
        bindVertexBuffer(0, buffer);
    }

    /**
     * Binds a vertex buffer to a buffer binding: 0 is the pipeline's {@link PipelineDesc#vertexLayout()}, 1 and up are
     * its {@link PipelineDesc#extraLayouts()}, in order.
     */
    void bindVertexBuffer(int binding, GpuBuffer buffer);

    /**
     * Indices are 32-bit unsigned integers.
     */
    void bindIndexBuffer(GpuBuffer buffer);

    void bindUniformBuffer(int binding, GpuBuffer buffer);

    void bindTexture(int binding, GpuTexture texture);

    default void draw(int vertexCount) {
        draw(vertexCount, 1, 0);
    }

    /**
     * Draws the vertices once per instance. A buffer whose layout is {@link VertexLayout.Rate#INSTANCE} supplies its
     * element number {@code firstInstance + i} to instance {@code i}.
     */
    void draw(int vertexCount, int instanceCount, int firstInstance);

    default void drawIndexed(int indexCount) {
        drawIndexed(indexCount, 1, 0);
    }

    /**
     * Draws the indexed mesh once per instance; see {@link #draw(int, int, int)}.
     */
    void drawIndexed(int indexCount, int instanceCount, int firstInstance);

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
