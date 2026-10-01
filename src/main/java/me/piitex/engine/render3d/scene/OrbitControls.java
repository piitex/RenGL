package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;

/**
 * Mouse control that turns a {@link Camera} around a point: drag to orbit, scroll to zoom. Hand it to the viewport
 * that shows the scene:
 * <pre>
 * Scene3D scene = new Scene3D();
 * OrbitControls orbit = new OrbitControls(scene.camera());
 * viewport.setContent(scene);
 * viewport.setInputListener(orbit);
 * </pre>
 * The controls own the camera's position once attached: they move it to match their orbit whenever the mouse does,
 * and a position set on the camera directly is overwritten then. Use {@link #setTarget}, {@link #setDistance} and
 * {@link #frame} to steer it from code. The camera's up must stay +Y.
 */
public final class OrbitControls implements Viewport3D.InputListener {
    private final Camera camera;
    private Vec3 target;
    private float distance;
    private float yaw;
    private float pitch;

    private float rotateSpeed = 0.01f;
    private float zoomSpeed = 0.1f;
    private float minDistance = 0.05f;
    private float maxDistance = 1000f;
    private static final float MAX_PITCH = (float) Math.toRadians(89);

    /**
     * Starts from the camera's current position and target.
     */
    public OrbitControls(Camera camera) {
        this.camera = camera;
        this.target = camera.getTarget();
        Vec3 offset = camera.getPosition().sub(target);
        distance = Math.max(minDistance, offset.length());
        yaw = (float) Math.atan2(offset.x(), offset.z());
        pitch = clampPitch((float) Math.asin(Math.max(-1f, Math.min(1f, offset.y() / distance))));
        apply();
    }

    private static float clampPitch(float pitch) {
        return Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
    }

    private void apply() {
        float cp = (float) Math.cos(pitch);
        Vec3 offset = new Vec3(cp * (float) Math.sin(yaw), (float) Math.sin(pitch), cp * (float) Math.cos(yaw)).mul(distance);
        camera.lookAt(target.add(offset), target);
    }

    public Vec3 getTarget() {
        return target;
    }

    public OrbitControls setTarget(Vec3 target) {
        this.target = target;
        apply();
        return this;
    }

    public float getDistance() {
        return distance;
    }

    public OrbitControls setDistance(float distance) {
        this.distance = Math.max(minDistance, Math.min(maxDistance, distance));
        apply();
        return this;
    }

    /**
     * Turns around the target; angles in radians. Yaw 0 looks from +Z, pitch 0 from the horizon, positive pitch from
     * above.
     */
    public OrbitControls setAngles(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = clampPitch(pitch);
        apply();
        return this;
    }

    /**
     * Points at the middle of {@code bounds} from far enough away to see all of it, and moves the camera's clip
     * planes to suit its size.
     */
    public OrbitControls frame(Bounds bounds) {
        if (bounds == null) return this;
        float radius = Math.max(bounds.radius(), 1e-3f);
        float half = (float) Math.toRadians(camera.getFovYDegrees()) * 0.5f;
        target = bounds.center();
        distance = Math.max(minDistance, Math.min(maxDistance, radius / (float) Math.sin(Math.min(half, 1.2f)) * 1.15f));
        camera.setClip(Math.max(distance * 0.01f, 1e-4f), distance + radius * 20f);
        apply();
        return this;
    }

    /**
     * Radians of turn per point dragged. Default 0.01.
     */
    public OrbitControls setRotateSpeed(float rotateSpeed) {
        this.rotateSpeed = rotateSpeed;
        return this;
    }

    /**
     * How strongly the wheel zooms: each step of 1 scales the distance by {@code e^-zoomSpeed}. Default 0.1.
     */
    public OrbitControls setZoomSpeed(float zoomSpeed) {
        this.zoomSpeed = zoomSpeed;
        return this;
    }

    public OrbitControls setDistanceLimits(float min, float max) {
        this.minDistance = min;
        this.maxDistance = max;
        distance = Math.max(min, Math.min(max, distance));
        apply();
        return this;
    }

    @Override
    public void dragged(double x, double y, double dx, double dy) {
        yaw -= (float) dx * rotateSpeed;
        pitch = clampPitch(pitch + (float) dy * rotateSpeed);
        apply();
    }

    @Override
    public void scrolled(double dx, double dy, double x, double y) {
        distance = Math.max(minDistance, Math.min(maxDistance, distance * (float) Math.exp(-dy * zoomSpeed)));
        apply();
    }
}
