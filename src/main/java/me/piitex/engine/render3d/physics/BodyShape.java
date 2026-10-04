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

    /**
     * The shape stretched by the node's scale, taken into the shape's own turned frame. Turning the scale can flip its
     * signs, which would mirror a hull or mesh, so each of the shape's axes takes its size from the turned scale and its
     * sign from the node axis it lies most along.
     */
    BodyShape scaled(Vec3 scale) {
        Vec3 size = rotation.conjugate().rotate(new Vec3(Math.abs(scale.x()), Math.abs(scale.y()), Math.abs(scale.z())));
        float[] stretch = {Math.abs(size.x()), Math.abs(size.y()), Math.abs(size.z())};
        for (int i = 0; i < 3; i++) {
            Vec3 axis = rotation.rotate(i == 0 ? Vec3.X : i == 1 ? Vec3.UP : Vec3.Z);
            float ax = Math.abs(axis.x()), ay = Math.abs(axis.y()), az = Math.abs(axis.z());
            float sign = ax >= ay && ax >= az ? scale.x() : ay >= az ? scale.y() : scale.z();
            if (sign < 0f) stretch[i] = -stretch[i];
        }
        // Rounding in the turn shouldn't make a 1 into 0.99999994 and rebuild a hull or mesh for nothing.
        for (int i = 0; i < 3; i++) if (Math.abs(Math.abs(stretch[i]) - 1f) < 1e-6f) stretch[i] = Math.copySign(1f, stretch[i]);
        return new BodyShape(shape.scaled(new Vec3(stretch[0], stretch[1], stretch[2])), offset.mul(scale), rotation);
    }
}
