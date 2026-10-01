package me.piitex.engine.render3d.math;

/**
 * An immutable rotation as a unit quaternion.
 */
public record Quat(float x, float y, float z, float w) {
    public static final Quat IDENTITY = new Quat(0, 0, 0, 1);

    /**
     * A rotation of {@code radians} around {@code axis}, counter-clockwise when looking down the axis toward the origin.
     */
    public static Quat fromAxisAngle(Vec3 axis, float radians) {
        Vec3 a = axis.normalized();
        float s = (float) Math.sin(radians * 0.5f);
        return new Quat(a.x() * s, a.y() * s, a.z() * s, (float) Math.cos(radians * 0.5f));
    }

    /**
     * Yaw around Y, then pitch around X, then roll around Z, in radians.
     */
    public static Quat fromEuler(float pitch, float yaw, float roll) {
        return fromAxisAngle(Vec3.UP, yaw).mul(fromAxisAngle(Vec3.X, pitch)).mul(fromAxisAngle(Vec3.Z, roll));
    }

    /**
     * This rotation applied after {@code o}: {@code a.mul(b).rotate(v)} is {@code a.rotate(b.rotate(v))}.
     */
    public Quat mul(Quat o) {
        return new Quat(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }

    public Quat normalized() {
        float length = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        return length < 1e-12f ? IDENTITY : new Quat(x / length, y / length, z / length, w / length);
    }

    public Vec3 rotate(Vec3 v) {
        Vec3 q = new Vec3(x, y, z);
        Vec3 t = q.cross(v).mul(2f);
        return v.add(t.mul(w)).add(q.cross(t));
    }

    public Mat4 toMatrix() {
        float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        float[] m = new float[16];
        m[0] = 1 - 2 * (yy + zz);
        m[1] = 2 * (xy + wz);
        m[2] = 2 * (xz - wy);
        m[4] = 2 * (xy - wz);
        m[5] = 1 - 2 * (xx + zz);
        m[6] = 2 * (yz + wx);
        m[8] = 2 * (xz + wy);
        m[9] = 2 * (yz - wx);
        m[10] = 1 - 2 * (xx + yy);
        m[15] = 1;
        return new Mat4(m);
    }
}
