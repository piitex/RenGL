package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Ray;
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

    /**
     * The ray from the camera through a point of the picture, for finding what is under the mouse. The point is in
     * the same units as the picture's size, from its top-left corner, as the mouse positions of a
     * {@link me.piitex.engine.render3d.Viewport3D.InputListener} are, so pass the viewport's own size.
     *
     * @param width  the picture's width, which with its height gives the aspect ratio the scene is drawn with
     * @param height the picture's height
     */
    public Ray screenRay(double x, double y, double width, double height) {
        double ndcX = 2.0 * x / Math.max(1e-6, width) - 1.0;
        double ndcY = 1.0 - 2.0 * y / Math.max(1e-6, height);
        double tan = Math.tan(fovY * 0.5);
        double aspect = width / Math.max(1e-6, height);
        Vec3 forward = target.sub(position).normalized();
        Vec3 right = forward.cross(up).normalized();
        Vec3 above = right.cross(forward);
        Vec3 direction = forward.add(right.mul((float) (ndcX * tan * aspect))).add(above.mul((float) (ndcY * tan)));
        return Ray.of(position, direction);
    }
}
