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

    @Override
    void close();
}
