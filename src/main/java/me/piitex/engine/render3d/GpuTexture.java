package me.piitex.engine.render3d;

/**
 * A 2D RGBA8 texture, sampled with linear filtering and repeat wrapping.
 */
public interface GpuTexture extends AutoCloseable {
    int width();

    int height();

    @Override
    void close();
}
