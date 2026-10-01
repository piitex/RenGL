package me.piitex.engine.render3d.math;

/**
 * An immutable 3D vector. Coordinates are right-handed with +Y up, and the camera looks down -Z by default.
 */
public record Vec3(float x, float y, float z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 ONE = new Vec3(1, 1, 1);
    public static final Vec3 UP = new Vec3(0, 1, 0);
    public static final Vec3 X = new Vec3(1, 0, 0);
    public static final Vec3 Z = new Vec3(0, 0, 1);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 mul(float s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public Vec3 negate() {
        return new Vec3(-x, -y, -z);
    }

    public float dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * The vector scaled to length 1, or {@link #ZERO} when it has no length.
     */
    public Vec3 normalized() {
        float length = length();
        return length < 1e-12f ? ZERO : mul(1f / length);
    }

    public Vec3 lerp(Vec3 o, float t) {
        return new Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }
}
