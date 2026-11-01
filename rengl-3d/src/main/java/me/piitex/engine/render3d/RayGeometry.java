package me.piitex.engine.render3d;

/**
 * The triangles of one mesh, built into the structure the GPU traces rays through (a bottom-level acceleration structure).
 * Made by {@link GpuDevice#createRayGeometry}; place it in the world with a {@link RayInstance} in a {@link RayScene}. The
 * triangles are fixed: a mesh that changes shape needs a new one.
 */
public interface RayGeometry extends AutoCloseable {
    int triangleCount();

    /**
     * The GPU address of the vertices as they were given to {@link GpuDevice#createRayGeometry}, for a shader that reads what a
     * ray hit through a buffer reference.
     */
    long vertexAddress();

    /**
     * The GPU address of the indices (32-bit, three to a triangle).
     */
    long indexAddress();

    /**
     * Frees the structure once the GPU is done with the frames that could still trace through it.
     */
    @Override
    void close();
}
