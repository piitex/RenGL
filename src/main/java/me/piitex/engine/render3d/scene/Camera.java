package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;

/**
 * A perspective camera: where it is, what it looks at, and its lens.
 */
public final class Camera {
    private Vec3 position = new Vec3(0, 1.5f, 4f);
    private Vec3 target = Vec3.ZERO;
    private Vec3 up = Vec3.UP;
    private float fovY = (float) Math.toRadians(60);
    private float near = 0.1f;
    private float far = 200f;

    public Vec3 getPosition() {
        return position;
    }

    public Camera setPosition(Vec3 position) {
        this.position = position;
        return this;
    }

    public Vec3 getTarget() {
        return target;
    }

    public Camera setTarget(Vec3 target) {
        this.target = target;
        return this;
    }

    /**
     * Moves the camera to {@code position} and points it at {@code target}.
     */
    public Camera lookAt(Vec3 position, Vec3 target) {
        this.position = position;
        this.target = target;
        return this;
    }

    public Vec3 getUp() {
        return up;
    }

    public Camera setUp(Vec3 up) {
        this.up = up;
        return this;
    }

    public float getFovYDegrees() {
        return (float) Math.toDegrees(fovY);
    }

    /**
     * The vertical field of view, between 1 and 170 degrees.
     */
    public Camera setFovYDegrees(float degrees) {
        this.fovY = (float) Math.toRadians(Math.max(1f, Math.min(170f, degrees)));
        return this;
    }

    public float getNear() {
        return near;
    }

    public float getFar() {
        return far;
    }

    /**
     * What the camera sees lies between these distances. Keep the ratio modest, since depth precision goes with it.
     */
    public Camera setClip(float near, float far) {
        if (near <= 0 || far <= near) throw new IllegalArgumentException("Need 0 < near < far");
        this.near = near;
        this.far = far;
        return this;
    }

    public Mat4 view() {
        return Mat4.lookAt(position, target, up);
    }

    /**
     * @param aspect width divided by height of the picture
     */
    public Mat4 projection(float aspect) {
        return Mat4.perspective(fovY, aspect, near, far);
    }
}
