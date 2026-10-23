package me.piitex.engine.render3d.gl;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.*;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.function.LongSupplier;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.GL_MAX_SAMPLES;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

/**
 * {@link GpuDevice} on OpenGL 3.3 core. It uses whatever context is current on the calling thread, which is the
 * window's own: Skia draws with the same context, so call Skia's {@code resetGLAll} after a 3D pass and before Skia
 * draws again.
 */
public final class GlDevice implements GpuDevice {
    static final int MAX_ATTRIBUTES = 16;

    final int vertexArray;
    private final String name;
    private final LongSupplier frames;
    private final int maxSamples;
    private final float anisotropy;

    /**
     * @throws IllegalStateException when no OpenGL context is current or it is older than 3.3
     */
    public GlDevice(LongSupplier frames) {
        this.frames = frames;
        if (GL.getCapabilities() == null) GL.createCapabilities();
        if (!GL.getCapabilities().OpenGL33) {
            throw new IllegalStateException("The 3D layer needs OpenGL 3.3, this context is "
                    + glGetString(GL_VERSION));
        }
        name = glGetString(GL_RENDERER);
        vertexArray = glGenVertexArrays();
        maxSamples = floorPowerOfTwo(Math.min(8, Math.max(1, glGetInteger(GL_MAX_SAMPLES))));
        float supported = 1f;
        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            supported = glGetFloat(org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
        }
        anisotropy = Math.max(1f, Math.min(supported, Integer.getInteger("rengl.3d.anisotropy", 8)));
    }

    @Override
    public RenderAPI api() {
        return RenderAPI.OPENGL;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long frameNumber() {
        return frames.getAsLong();
    }

    @Override
    public void beginFrame() {
    }

    @Override
    public void endFrame() {
    }

    @Override
    public GpuBuffer createBuffer(BufferType type, ByteBuffer data) {
        return new GlBuffer(type, data.remaining(), data, false);
    }

    @Override
    public GpuBuffer createDynamicBuffer(BufferType type, long size) {
        return new GlBuffer(type, size, null, true);
    }

    @Override
    public GpuTexture createTexture(int width, int height, ByteBuffer rgba, boolean mipmaps) {
        return new GlTexture(width, height, rgba, mipmaps, anisotropy);
    }

    @Override
    public GpuTexture createHdrTexture(int width, int height, java.nio.FloatBuffer rgba, boolean mipmaps) {
        return new GlTexture(width, height, rgba, mipmaps, anisotropy);
    }

    @Override
    public GpuTexture createCubeTexture(int size, ByteBuffer[] faces, boolean mipmaps) {
        return new GlTexture(size, faces, mipmaps, anisotropy);
    }

    @Override
    public Pipeline createPipeline(PipelineDesc desc) {
        return new GlPipeline(desc);
    }

    @Override
    public RenderTarget createRenderTarget(int width, int height, boolean depth, int samples) {
        return new GlRenderTarget(width, height, depth, Math.min(floorPowerOfTwo(Math.max(1, samples)), maxSamples));
    }

    @Override
    public RenderTarget createSampledTarget(int width, int height, boolean depth, boolean linear, boolean hdr, int samples) {
        return new GlRenderTarget(width, height, depth, Math.min(floorPowerOfTwo(Math.max(1, samples)), maxSamples), true, linear, hdr);
    }

    @Override
    public int maxAnisotropy() {
        return (int) anisotropy;
    }

    @Override
    public int maxSamples() {
        return maxSamples;
    }

    private static int floorPowerOfTwo(int n) {
        return Integer.highestOneBit(Math.max(1, n));
    }

    @Override
    public RenderPass beginPass(RenderTarget target, float r, float g, float b, float a) {
        return new GlRenderPass(this, (GlRenderTarget) target, r, g, b, a);
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vertexArray);
    }
}
