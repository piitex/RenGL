package me.piitex.engine.render3d.gl;

import me.piitex.engine.render3d.*;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31C.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL31C.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL33C.glVertexAttribDivisor;
import static org.lwjgl.opengl.GL31C.glBindBufferBase;

final class GlRenderPass implements RenderPass {
    private final GlDevice device;
    private final GlRenderTarget target;
    private final int[] previousViewport = new int[4];
    private final int previousFramebuffer;
    private GlPipeline pipeline;
    private final GlBuffer[] vertexBuffers = new GlBuffer[4];
    private GlBuffer indexBuffer;
    private boolean attributesDirty;
    private int appliedFirstInstance;
    private boolean ended;

    GlRenderPass(GlDevice device, GlRenderTarget target, float r, float g, float b, float a) {
        this.device = device;
        this.target = target;
        previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, previousViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, target.drawFramebuffer);
        glViewport(0, 0, target.width(), target.height());
        // Skia shares this context and leaves its own state behind; take back what a pass depends on.
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        glClearColor(r, g, b, a);
        glClearDepth(1.0);
        glClear(target.hasDepth() ? GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT : GL_COLOR_BUFFER_BIT);
        glBindVertexArray(device.vertexArray);
    }

    @Override
    public void bindPipeline(Pipeline pipeline) {
        this.pipeline = (GlPipeline) pipeline;
        PipelineDesc desc = this.pipeline.desc;
        glUseProgram(this.pipeline.program);

        boolean depth = desc.depthTest() && target.hasDepth();
        if (depth) {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        glDepthMask(depth);

        if (desc.cullBackFaces()) {
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);
            glFrontFace(desc.frontClockwise() ? GL_CW : GL_CCW);
        } else {
            glDisable(GL_CULL_FACE);
        }

        if (desc.blend()) {
            glEnable(GL_BLEND);
            // Colour mixes by alpha; the alpha channel itself accumulates toward 1, since the UI treats the picture as premultiplied.
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }
        attributesDirty = true;
    }

    @Override
    public void bindVertexBuffer(int binding, GpuBuffer buffer) {
        if (binding < 0 || binding >= vertexBuffers.length) throw new IllegalArgumentException("Vertex buffer binding " + binding + " is out of range");
        vertexBuffers[binding] = (GlBuffer) buffer;
        attributesDirty = true;
    }

    @Override
    public void bindIndexBuffer(GpuBuffer buffer) {
        indexBuffer = (GlBuffer) buffer;
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer.id);
    }

    @Override
    public void bindUniformBuffer(int binding, GpuBuffer buffer) {
        glBindBufferBase(GL_UNIFORM_BUFFER, binding, ((GlBuffer) buffer).id);
    }

    @Override
    public void bindTexture(int binding, GpuTexture texture) {
        GlTexture gl = (GlTexture) texture;
        glActiveTexture(GL_TEXTURE0 + binding);
        glBindTexture(gl.target, gl.id);
    }

    /**
     * Vertex attribute pointers capture the buffer bound when they are set, so they are set at draw time, once both
     * the pipeline's layouts and the buffers are known. OpenGL 3.3 has no base instance, so an instanced buffer's
     * pointers start {@code firstInstance} elements in, which is why they are set again when that changes.
     */
    private void applyAttributes(int firstInstance) {
        if (pipeline == null) throw new IllegalStateException("Bind a pipeline before drawing");
        boolean instanced = !pipeline.desc.extraLayouts().isEmpty();
        if (!attributesDirty && !(instanced && firstInstance != appliedFirstInstance)) return;
        for (int i = 0; i < GlDevice.MAX_ATTRIBUTES; i++) {
            glDisableVertexAttribArray(i);
            glVertexAttribDivisor(i, 0);
        }
        applyLayout(0, pipeline.desc.vertexLayout(), firstInstance);
        for (int i = 0; i < pipeline.desc.extraLayouts().size(); i++) {
            applyLayout(i + 1, pipeline.desc.extraLayouts().get(i), firstInstance);
        }
        attributesDirty = false;
        appliedFirstInstance = firstInstance;
    }

    private void applyLayout(int binding, VertexLayout layout, int firstInstance) {
        GlBuffer buffer = binding < vertexBuffers.length ? vertexBuffers[binding] : null;
        if (buffer == null) throw new IllegalStateException("Bind a vertex buffer at binding " + binding + " before drawing");
        boolean perInstance = layout.rate() == VertexLayout.Rate.INSTANCE;
        long base = perInstance ? (long) firstInstance * layout.stride() : 0;
        glBindBuffer(GL_ARRAY_BUFFER, buffer.id);
        for (VertexLayout.Attribute attribute : layout.attributes()) {
            glEnableVertexAttribArray(attribute.location());
            glVertexAttribPointer(attribute.location(), attribute.format().components, GL_FLOAT, false,
                    layout.stride(), base + attribute.offset());
            if (perInstance) glVertexAttribDivisor(attribute.location(), 1);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstInstance) {
        applyAttributes(firstInstance);
        glDrawArraysInstanced(GL_TRIANGLES, 0, vertexCount, instanceCount);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstInstance) {
        if (indexBuffer == null) throw new IllegalStateException("Bind an index buffer before drawing");
        applyAttributes(firstInstance);
        glDrawElementsInstanced(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0, instanceCount);
    }

    @Override
    public void end() {
        if (ended) return;
        ended = true;
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB);
        glColorMask(true, true, true, true);
        target.resolve();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glDepthMask(true);
        glUseProgram(0);
        glBindVertexArray(0);
        glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer);
        glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
    }
}
