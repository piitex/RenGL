package me.piitex.engine.render3d;

import java.nio.ByteBuffer;

/**
 * A block of GPU memory holding vertices, 32-bit indices or uniforms.
 */
public interface GpuBuffer extends AutoCloseable {
    BufferType type();

    long size();

    /**
     * Replaces the contents (from the buffer's position to its limit). Only buffers made with
     * {@link GpuDevice#createDynamicBuffer} accept it, and only between {@link GpuDevice#beginFrame()} and
     * {@link GpuDevice#endFrame()}; the new contents are what passes recorded afterwards in that frame see.
     */
    void update(ByteBuffer data);

    @Override
    void close();
}
