package me.piitex.engine.render3d;

import java.util.Objects;

/**
 * One placement of a {@link RayGeometry} in a {@link RayScene}.
 *
 * @param geometry  the triangles
 * @param transform the 4x4 matrix that places them, 16 floats in column-major order like
 *                  {@link me.piitex.engine.render3d.math.Mat4#toArray()}; only the upper 3x4 part is used
 */
public record RayInstance(RayGeometry geometry, float[] transform) {
    public RayInstance {
        Objects.requireNonNull(geometry);
        if (transform.length < 16) throw new IllegalArgumentException("A transform is 16 floats");
    }
}
