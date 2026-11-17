package me.piitex.engine.render3d;

import java.nio.ByteBuffer;

/**
 * Compute work, made by {@link GpuDevice#beginCompute()} and handed to the GPU by {@link #end()}. It runs in order with the
 * render passes: after those made before it (it waits for them to finish with the buffers it writes) and before those made
 * after it, which see everything it wrote, as draw commands, vertex data or shader reads.
 * <p>
 * Work recorded one after the other may overlap on the GPU; {@link #barrier()} makes what follows wait for what came before.
 */
public interface ComputePass extends AutoCloseable {
    void bindPipeline(ComputePipeline pipeline);

    void bindStorageBuffer(int binding, GpuBuffer buffer);

    void bindUniformBuffer(int binding, GpuBuffer buffer);

    void bindTexture(int binding, GpuTexture texture);

    /**
     * Sets the shader's {@code push_constant} block from the data's position to its limit (at most 128 bytes), for the
     * dispatches that follow.
     */
    void pushConstants(ByteBuffer data);

    /**
     * Runs the bound pipeline over that many work groups in each direction.
     */
    void dispatch(int groupsX, int groupsY, int groupsZ);

    /**
     * Runs the bound pipeline over as many work groups as a storage buffer says at {@code offset} (a multiple of 4): three
     * 32-bit counts, x, y and z, which earlier work wrote (before a {@link #barrier()}). Any of them 0 runs nothing.
     */
    void dispatchIndirect(GpuBuffer buffer, long offset);

    /**
     * Sets {@code size} bytes of a storage buffer from {@code offset} (both multiples of 4) to a 32-bit value, in order with
     * the dispatches around it.
     */
    void fill(GpuBuffer buffer, long offset, long size, int value);

    /**
     * Copies bytes from a storage buffer into another, or into a {@linkplain GpuDevice#createReadbackBuffer readback buffer}.
     */
    void copy(GpuBuffer from, long fromOffset, GpuBuffer to, long toOffset, long size);

    /**
     * The dispatches, fills and copies after this wait for those before and see what they wrote.
     */
    void barrier();

    /**
     * Submits the work.
     */
    void end();

    @Override
    default void close() {
        end();
    }
}
