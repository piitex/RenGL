package me.piitex.engine.render3d;

import java.util.List;

/**
 * Every {@link RayInstance} of a world, in the structure a shader traces rays through (a top-level acceleration structure).
 * Made by {@link GpuDevice#createRayScene}, rebuilt from the instances once per frame with {@link #update} and bound to a
 * shader's {@code accelerationStructureEXT} with {@link RenderPass#bindAccelerationStructure}.
 * <p>
 * The scene keeps a copy for each frame the GPU can still be working on, so updating it never changes what an earlier frame
 * is tracing through.
 */
public interface RayScene extends AutoCloseable {
    /**
     * The most instances {@link #update} takes.
     */
    int capacity();

    /**
     * Replaces the instances, for the frame being recorded. Call it between {@link GpuDevice#beginFrame()} and
     * {@link GpuDevice#endFrame()}, before the pass that traces through it. Instances beyond {@link #capacity()} are left out.
     */
    void update(List<RayInstance> instances);

    /**
     * The GPU address of the table {@link #update} wrote for the frame being recorded, for a shader to read through a buffer
     * reference: one record of 96 bytes for each instance, in the order given, the instance's {@code gl_InstanceCustomIndexEXT}
     * being its place. A record is the address of the geometry's vertices and of its indices (8 bytes each) and the
     * instance's {@link RayInstance#data()} (20 floats, zero where it gave none).
     */
    long dataAddress();

    @Override
    void close();
}
