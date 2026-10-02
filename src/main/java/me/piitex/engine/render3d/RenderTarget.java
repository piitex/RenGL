package me.piitex.engine.render3d;

import java.nio.ByteBuffer;

/**
 * An offscreen RGBA8 color image with an optional depth buffer, optionally multisampled: the thing a {@link RenderPass} draws into, and what the
 * UI layer later draws as an image.
 */
public interface RenderTarget extends AutoCloseable {
    int width();

    int height();

    boolean hasDepth();

    /**
     * The samples per pixel it is drawn with; 1 when it is not multisampled.
     */
    int samples();

    /**
     * True for a target made with {@link GpuDevice#createSampledTarget}, whose picture later passes can read.
     */
    default boolean isSampled() {
        return false;
    }

    /**
     * The picture as a texture, to bind in a later pass: a shadow map, a mirror, a minimap. Only for a target made with
     * {@link GpuDevice#createSampledTarget}, and not while a pass is drawing into it. The texture belongs to the
     * target and is freed with it.
     *
     * @throws UnsupportedOperationException for any other target, whose image is reserved for the UI layer
     */
    default GpuTexture texture() {
        throw new UnsupportedOperationException("Only a target made with createSampledTarget can be read as a texture");
    }

    /**
     * True when row 0 of the image in GPU memory is the bottom of the picture (OpenGL), false when it is the top
     * (Vulkan). The UI layer needs this to draw the image the right way up.
     */
    boolean isBottomLeftOrigin();

    /**
     * Copies the image to the CPU as tightly packed RGBA8 with the top row first, whatever the backend. Waits for the
     * GPU, so it is for screenshots and tests, not for every frame.
     */
    ByteBuffer readPixels();

    @Override
    void close();
}
