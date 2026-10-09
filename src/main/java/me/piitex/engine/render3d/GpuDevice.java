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

    /**
     * A texture of linear light with a range far beyond white, for a sky with a real sun in it. Four channels of 16-bit
     * floats (values up to 65504), whole picture top row first, repeat wrapping, smoothly filtered.
     *
     * @param rgba    {@code width * height * 4} floats, tightly packed, from the buffer's position
     * @param mipmaps also make the smaller versions of the picture and filter between them
     */
    GpuTexture createHdrTexture(int width, int height, java.nio.FloatBuffer rgba, boolean mipmaps);

    /**
     * A cube map from six square faces, for skies and reflections. Faces are RGBA8, top row first, each
     * {@code size * size * 4} bytes, in the order +X, -X, +Y, -Y, +Z, -Z, laid out the way OpenGL and Vulkan expect
     * (the common skybox image sets work as they are). Sample it with a {@code samplerCube} and a direction.
     *
     * @param mipmaps also make the smaller versions, which stops a distant or shrunk cube from shimmering
     */
    GpuTexture createCubeTexture(int size, ByteBuffer[] faces, boolean mipmaps);

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
     * A target with colour and optional depth, one sample per pixel, whose picture a later pass can read with
     * {@link RenderTarget#texture()}: a shadow map is drawn into one. It can not be shown by a
     * {@link Viewport3D}, because it does not have the layout the UI layer needs.
     *
     * @param linear filter the texture linearly when it is read, or take the nearest pixel (what a map of packed
     *               values such as depth needs)
     */
    RenderTarget createSampledTarget(int width, int height, boolean depth, boolean linear);

    /**
     * The most samples a render target can have on this GPU: at least 1, at most 8.
     */
    int maxSamples();

    /**
     * The highest anisotropic filtering level the textures use: 1 when the GPU has none. Mipmapped textures keep their
     * detail at a slant (a floor stretching away from the camera) at the level of this setting, which is 8 or the GPU's
     * limit if lower. The system property {@code rengl.3d.anisotropy} overrides it; 1 turns it off.
     */
    int maxAnisotropy();

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
