package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

/**
 * A 3x3 matrix, row by row, for inertia tensors.
 */
record Mat3(float xx, float xy, float xz, float yx, float yy, float yz, float zx, float zy, float zz) {
    static final Mat3 ZERO = new Mat3(0, 0, 0, 0, 0, 0, 0, 0, 0);

    static Mat3 diagonal(Vec3 d) {
        return new Mat3(d.x(), 0, 0, 0, d.y(), 0, 0, 0, d.z());
    }

    static Mat3 rotation(Quat q) {
        float x = q.x(), y = q.y(), z = q.z(), w = q.w();
        float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        return new Mat3(
                1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
                2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
                2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy));
    }

    /**
     * The extra inertia of a point mass {@code mass} held {@code d} from the pivot (the parallel axis theorem).
     */
    static Mat3 pointMass(float mass, Vec3 d) {
        float dd = d.lengthSquared();
        return new Mat3(
                mass * (dd - d.x() * d.x()), -mass * d.x() * d.y(), -mass * d.x() * d.z(),
                -mass * d.y() * d.x(), mass * (dd - d.y() * d.y()), -mass * d.y() * d.z(),
                -mass * d.z() * d.x(), -mass * d.z() * d.y(), mass * (dd - d.z() * d.z()));
    }

    Mat3 add(Mat3 o) {
        return new Mat3(xx + o.xx, xy + o.xy, xz + o.xz, yx + o.yx, yy + o.yy, yz + o.yz, zx + o.zx, zy + o.zy, zz + o.zz);
    }

    Mat3 mul(float s) {
        return new Mat3(xx * s, xy * s, xz * s, yx * s, yy * s, yz * s, zx * s, zy * s, zz * s);
    }

    Mat3 mul(Mat3 o) {
        return new Mat3(
                xx * o.xx + xy * o.yx + xz * o.zx, xx * o.xy + xy * o.yy + xz * o.zy, xx * o.xz + xy * o.yz + xz * o.zz,
                yx * o.xx + yy * o.yx + yz * o.zx, yx * o.xy + yy * o.yy + yz * o.zy, yx * o.xz + yy * o.yz + yz * o.zz,
                zx * o.xx + zy * o.yx + zz * o.zx, zx * o.xy + zy * o.yy + zz * o.zy, zx * o.xz + zy * o.yz + zz * o.zz);
    }

    Vec3 mul(Vec3 v) {
        return new Vec3(xx * v.x() + xy * v.y() + xz * v.z(), yx * v.x() + yy * v.y() + yz * v.z(), zx * v.x() + zy * v.y() + zz * v.z());
    }

    /**
     * {@code this^T * v}: for a rotation, the vector taken back into the turned frame.
     */
    Vec3 mulTransposed(Vec3 v) {
        return new Vec3(xx * v.x() + yx * v.y() + zx * v.z(), xy * v.x() + yy * v.y() + zy * v.z(), xz * v.x() + yz * v.y() + zz * v.z());
    }

    /**
     * Column {@code i}: for a rotation, where the frame's X, Y or Z axis points.
     */
    Vec3 column(int i) {
        return switch (i) {
            case 0 -> new Vec3(xx, yx, zx);
            case 1 -> new Vec3(xy, yy, zy);
            default -> new Vec3(xz, yz, zz);
        };
    }

    Mat3 transpose() {
        return new Mat3(xx, yx, zx, xy, yy, zy, xz, yz, zz);
    }

    /**
     * {@code r * this * r^T}: a tensor given in a turned frame, expressed in the outer one.
     */
    Mat3 rotated(Mat3 r) {
        return r.mul(this).mul(r.transpose());
    }

    /**
     * The inverse, or {@link #ZERO} when the matrix can't be inverted.
     */
    Mat3 inverse() {
        float cxx = yy * zz - yz * zy, cxy = yz * zx - yx * zz, cxz = yx * zy - yy * zx;
        float det = xx * cxx + xy * cxy + xz * cxz;
        if (Math.abs(det) < 1e-20f) return ZERO;
        float inv = 1f / det;
        return new Mat3(
                cxx * inv, (xz * zy - xy * zz) * inv, (xy * yz - xz * yy) * inv,
                cxy * inv, (xx * zz - xz * zx) * inv, (xz * yx - xx * yz) * inv,
                cxz * inv, (xy * zx - xx * zy) * inv, (xx * yy - xy * yx) * inv);
    }

    Vec3 diagonal() {
        return new Vec3(xx, yy, zz);
    }
}
