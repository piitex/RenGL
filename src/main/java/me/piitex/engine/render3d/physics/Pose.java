package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

/**
 * Where a shape is in the world: a position and a rotation, with the rotation also kept as a matrix.
 */
record Pose(Vec3 position, Quat rotation, Mat3 basis) {
    Pose(Vec3 position, Quat rotation) {
        this(position, rotation, Mat3.rotation(rotation));
    }

    Vec3 toWorld(Vec3 local) {
        return position.add(basis.mul(local));
    }

    Vec3 toLocal(Vec3 world) {
        return basis.mulTransposed(world.sub(position));
    }

    Vec3 rotate(Vec3 local) {
        return basis.mul(local);
    }

    Vec3 unrotate(Vec3 world) {
        return basis.mulTransposed(world);
    }

    Vec3 axis(int i) {
        return basis.column(i);
    }
}
