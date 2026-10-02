package me.piitex.engine.render3d.gl;

import me.piitex.engine.render3d.GpuTexture;
import me.piitex.engine.render3d.RenderTarget;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.*;

public final class GlRenderTarget implements RenderTarget {
    private final int width, height, samples;
    private final boolean depth, sampled;
    private GlTexture texture;
    /**
     * The framebuffer holding the finished picture: the texture. Skia wraps this one.
     */
    int framebuffer;
    /**
     * The framebuffer a pass draws into. The same as {@link #framebuffer} unless the target is multisampled, when it
     * holds multisampled renderbuffers that {@link #resolve()} copies into the texture.
     */
    int drawFramebuffer;
    int colorTexture;
    private int depthBuffer, msaaColor;

    GlRenderTarget(int width, int height, boolean depth, int samples) {
        this(width, height, depth, samples, false, true);
    }

    /**
     * @param sampled the picture is read by later passes, so it needs no multisampling and the filter is the caller's
     *                choice
     */
    GlRenderTarget(int width, int height, boolean depth, int samples, boolean sampled, boolean linear) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.depth = depth;
        this.samples = sampled ? 1 : samples;
        this.sampled = sampled;
        samples = this.samples;

        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        colorTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, this.width, this.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
        if (depth) {
            depthBuffer = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
            if (samples <= 1) {
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, this.width, this.height);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);
            }
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
        }
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        drawFramebuffer = framebuffer;
        if (samples > 1) {
            // The picture is drawn into multisampled renderbuffers and resolved into the texture when the pass ends.
            // Colour and depth need the same sample count.
            drawFramebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, drawFramebuffer);
            msaaColor = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, msaaColor);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, this.width, this.height);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msaaColor);
            if (depth) {
                glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
                glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH_COMPONENT24, this.width, this.height);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);
            }
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
            if (status == GL_FRAMEBUFFER_COMPLETE) status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, previous);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            close();
            throw new IllegalStateException("OpenGL framebuffer is incomplete (0x" + Integer.toHexString(status) + ")");
        }
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean hasDepth() {
        return depth;
    }

    @Override
    public int samples() {
        return samples;
    }

    @Override
    public boolean isSampled() {
        return sampled;
    }

    @Override
    public GpuTexture texture() {
        if (!sampled) return RenderTarget.super.texture();
        if (texture == null) texture = new GlTexture(colorTexture, width, height);
        return texture;
    }

    /**
     * Averages the multisampled picture into the texture. The caller keeps its own framebuffer binding.
     */
    void resolve() {
        if (drawFramebuffer == framebuffer) return;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, drawFramebuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    @Override
    public boolean isBottomLeftOrigin() {
        return true;
    }

    @Override
    public ByteBuffer readPixels() {
        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int rowBytes = width * 4;
        ByteBuffer raw = MemoryUtil.memAlloc(rowBytes * height);
        ByteBuffer flipped = MemoryUtil.memAlloc(rowBytes * height);
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, raw);
            glBindFramebuffer(GL_FRAMEBUFFER, previous);
            // OpenGL's first row is the bottom of the picture; callers get the top row first.
            for (int row = 0; row < height; row++) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(raw) + (long) (height - 1 - row) * rowBytes,
                        MemoryUtil.memAddress(flipped) + (long) row * rowBytes, rowBytes);
            }
            return flipped;
        } catch (RuntimeException e) {
            MemoryUtil.memFree(flipped);
            throw e;
        } finally {
            MemoryUtil.memFree(raw);
        }
    }

    /**
     * The OpenGL framebuffer object the pass draws into, for wrapping in Skia.
     */
    public int framebufferId() {
        return framebuffer;
    }

    /**
     * The OpenGL texture the pass draws into.
     */
    public int textureId() {
        return colorTexture;
    }

    @Override
    public void close() {
        if (drawFramebuffer != 0 && drawFramebuffer != framebuffer) glDeleteFramebuffers(drawFramebuffer);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (colorTexture != 0) glDeleteTextures(colorTexture);
        if (depthBuffer != 0) glDeleteRenderbuffers(depthBuffer);
        if (msaaColor != 0) glDeleteRenderbuffers(msaaColor);
        framebuffer = drawFramebuffer = colorTexture = depthBuffer = msaaColor = 0;
    }
}
