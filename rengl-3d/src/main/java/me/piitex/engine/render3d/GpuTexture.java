package me.piitex.engine.render3d;

/**
 * A 2D RGBA8 texture, sampled with linear filtering and repeat wrapping, and trilinear filtering when it has mipmaps.
 */
public interface GpuTexture extends AutoCloseable {
    int width();

    int height();

    /**
     * The number of levels of detail: 1 for a texture without mipmaps, otherwise down to one pixel.
     */
    int mipLevels();

    /**
     * Replaces the whole picture, same size and format as it was made with, and rebuilds the mipmaps if it has them.
     * Meant for a texture that changes every frame, such as a screen: it costs one copy of the picture, not a new
     * texture. Safe to call while earlier frames are still on the GPU, and the next draw sees the new picture.
     *
     * @param rgba {@code width * height * 4} bytes of tightly packed RGBA8, top row first, from the buffer's position
     * @throws UnsupportedOperationException for a texture that can not be written (a render target's, a cube map, a
     *                                       floating point one)
     */
    default void update(java.nio.ByteBuffer rgba) {
        throw new UnsupportedOperationException("This texture can not be updated");
    }

    @Override
    void close();
}
