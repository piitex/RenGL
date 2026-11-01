package me.piitex.engine.render3d;

import java.util.Objects;

/**
 * One placement of a {@link RayGeometry} in a {@link RayScene}.
 *
 * @param geometry  the triangles
 * @param transform the 4x4 matrix that places them, 16 floats in column-major order like
 *                  {@link me.piitex.engine.render3d.math.Mat4#toArray()}; only the upper 3x4 part is used
 * @param mask      which rays see it: a ray hits an instance when its cull mask shares a bit with this (255 for every ray)
 * @param data      up to {@link #DATA_FLOATS} floats of the caller's own for a shader to read at a hit (a material, say), or null
 */
public record RayInstance(RayGeometry geometry, float[] transform, int mask, float[] data) {
    /**
     * The most floats of {@link #data()} a scene keeps for each instance.
     */
    public static final int DATA_FLOATS = 20;

    public RayInstance {
        Objects.requireNonNull(geometry);
        if (transform.length < 16) throw new IllegalArgumentException("A transform is 16 floats");
        if (data != null && data.length > DATA_FLOATS) throw new IllegalArgumentException("An instance holds at most " + DATA_FLOATS + " floats of data");
    }

    public RayInstance(RayGeometry geometry, float[] transform) {
        this(geometry, transform, 0xFF, null);
    }
}
