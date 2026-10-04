package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

/**
 * A shape placed on a body: moved by {@code offset} and turned by {@code rotation} from the body's origin, in the body's
 * own space. Several of these make one solid object, such as a table from a top and four legs.
 */
public record BodyShape(Shape shape, Vec3 offset, Quat rotation) {
    public BodyShape {
        if (shape == null) throw new IllegalArgumentException("A body shape needs a shape");
        if (offset == null) offset = Vec3.ZERO;
        rotation = rotation == null ? Quat.IDENTITY : rotation.normalized();
    }

    public BodyShape(Shape shape) {
        this(shape, Vec3.ZERO, Quat.IDENTITY);
    }

    BodyShape scaled(Vec3 scale) {
        return new BodyShape(shape.scaled(rotation.conjugate().rotate(scale)), offset.mul(scale), rotation);
    }
}
