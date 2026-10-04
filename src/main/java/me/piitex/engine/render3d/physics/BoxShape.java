package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;

/**
 * A box reaching {@code halfExtents} from its centre along each axis, so a 1 m cube has half extents of 0.5.
 */
public record BoxShape(Vec3 halfExtents) implements Shape {
    public BoxShape {
        if (!(halfExtents.x() > 0f && halfExtents.y() > 0f && halfExtents.z() > 0f)) {
            throw new IllegalArgumentException("Every half extent must be above 0, not " + halfExtents);
        }
    }

    public BoxShape(float halfX, float halfY, float halfZ) {
        this(new Vec3(halfX, halfY, halfZ));
    }

    /**
     * A box of the given full size, such as {@code BoxShape.ofSize(1, 1, 1)} for a 1 m cube.
     */
    public static BoxShape ofSize(float width, float height, float depth) {
        return new BoxShape(width * 0.5f, height * 0.5f, depth * 0.5f);
    }

    @Override
    public float volume() {
        return 8f * halfExtents.x() * halfExtents.y() * halfExtents.z();
    }

    @Override
    public Vec3 unitInertia() {
        float xx = halfExtents.x() * halfExtents.x(), yy = halfExtents.y() * halfExtents.y(), zz = halfExtents.z() * halfExtents.z();
        return new Vec3((yy + zz) / 3f, (xx + zz) / 3f, (xx + yy) / 3f);
    }

    @Override
    public Bounds bounds() {
        return new Bounds(halfExtents.negate(), halfExtents);
    }

    @Override
    public BoxShape scaled(Vec3 scale) {
        return new BoxShape(halfExtents.x() * Math.abs(scale.x()), halfExtents.y() * Math.abs(scale.y()), halfExtents.z() * Math.abs(scale.z()));
    }
}
