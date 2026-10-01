package me.piitex.engine.render3d;

import java.nio.ByteBuffer;

/**
 * An offscreen RGBA8 color image with an optional depth buffer: the thing a {@link RenderPass} draws into, and what the
 * UI layer later draws as an image.
 */
public interface RenderTarget extends AutoCloseable {
    int width();

    int height();

    boolean hasDepth();

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
