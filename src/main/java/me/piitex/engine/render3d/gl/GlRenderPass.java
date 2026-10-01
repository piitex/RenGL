package me.piitex.engine.render3d.gl;

import me.piitex.engine.render3d.*;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31C.glBindBufferBase;

final class GlRenderPass implements RenderPass {
    private final GlDevice device;
    private final GlRenderTarget target;
    private final int[] previousViewport = new int[4];
    private final int previousFramebuffer;
    private GlPipeline pipeline;
    private GlBuffer vertexBuffer, indexBuffer;
    private boolean attributesDirty;
    private boolean ended;

    GlRenderPass(GlDevice device, GlRenderTarget target, float r, float g, float b, float a) {
        this.device = device;
        this.target = target;
        previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, previousViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glViewport(0, 0, target.width(), target.height());
        glDisable(GL_SCISSOR_TEST);
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
            glFrontFace(GL_CCW);
        } else {
            glDisable(GL_CULL_FACE);
        }

        if (desc.blend()) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }
        attributesDirty = true;
    }

    @Override
    public void bindVertexBuffer(GpuBuffer buffer) {
        vertexBuffer = (GlBuffer) buffer;
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
        glActiveTexture(GL_TEXTURE0 + binding);
        glBindTexture(GL_TEXTURE_2D, ((GlTexture) texture).id);
    }

    /**
     * Vertex attribute pointers capture the buffer bound when they are set, so they are set at draw time, once both
     * the pipeline's layout and the buffer are known.
     */
    private void applyAttributes() {
        if (!attributesDirty) return;
        if (pipeline == null) throw new IllegalStateException("Bind a pipeline before drawing");
        if (vertexBuffer == null) throw new IllegalStateException("Bind a vertex buffer before drawing");
        VertexLayout layout = pipeline.desc.vertexLayout();
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.id);
        for (int i = 0; i < GlDevice.MAX_ATTRIBUTES; i++) glDisableVertexAttribArray(i);
        for (VertexLayout.Attribute attribute : layout.attributes()) {
            glEnableVertexAttribArray(attribute.location());
            glVertexAttribPointer(attribute.location(), attribute.format().components, GL_FLOAT, false,
                    layout.stride(), attribute.offset());
        }
        attributesDirty = false;
    }

    @Override
    public void draw(int vertexCount) {
        applyAttributes();
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
    }

    @Override
    public void drawIndexed(int indexCount) {
        if (indexBuffer == null) throw new IllegalStateException("Bind an index buffer before drawing");
        applyAttributes();
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
    }

    @Override
    public void end() {
        if (ended) return;
        ended = true;
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
