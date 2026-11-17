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

    /**
     * Copies what the GPU last wrote into a buffer made with {@link GpuDevice#createReadbackBuffer}, {@code into.remaining()}
     * bytes from the start, into {@code into} (from its position, which is left where it was). The buffer keeps one copy per
     * frame in flight: what this returns was written that many frames ago, by commands the GPU has finished.
     */
    default void read(ByteBuffer into) {
        throw new UnsupportedOperationException("Only a readback buffer can be read");
    }

    @Override
    void close();
}
