package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which lights get shadows this frame and works out the views each one is drawn from: one for a spot light, six
 * for a point light (one per side of a cube around it), and one per cascade for a directional light. Pure maths, no GPU.
 * <p>
 * Every view stores the offsets the shader needs in the units of its own tile, so they depend on the tile's size, which
 * is why the views are built after the atlas has settled the sizes.
 */
final class ShadowPlanner {
    private ShadowPlanner() {
    }

    /**
     * A light that wants shadows this frame, and the tile size it asked for.
     */
    record Candidate(Light light, int index, Shadows shadows, int size) {
    }

    /**
     * One picture of the world from a light's side.
     *
     * @param viewProj     world to the light's clip space
     * @param perspective  true when the map holds distance along the view axis scaled to 0..1 (spot and point), false for depth
     * @param normalOffset how far the shader moves a surface along its normal before looking it up, per unit of {@code w} for a
     *                     perspective view
     * @param depthBias    the depth the shader tolerates before calling a surface shadowed
     * @param planes       the view volume, to leave out casters that cannot show in it
     * @param rect         the view's tile in the atlas, set once the layout is known
     */
    static final class View {
        final Mat4 viewProj;
        final boolean perspective;
        final float near, far, normalOffset, depthBias;
        final float[] planes;
        ShadowAtlas.Rect rect;

        View(Mat4 viewProj, boolean perspective, float near, float far, float normalOffset, float depthBias) {
            this.viewProj = viewProj;
            this.perspective = perspective;
            this.near = near;
            this.far = far;
            this.normalOffset = normalOffset;
            this.depthBias = depthBias;
            this.planes = SceneRenderer.frustumPlanes(viewProj);
        }
    }

    /**
     * The views of one light, in the order the shader indexes them: a point light's faces are +X, -X, +Y, -Y, +Z, -Z, a
     * directional light's cascades run near to far.
     *
     * @param splits for cascades, how far from the camera (along its view direction) each cascade reaches
     */
    record LightPlan(Light light, int index, Shadows shadows, int size, List<View> views, float[] splits) {
    }

    /**
     * How many views a light needs.
     */
    static int viewCount(Light light, Shadows shadows) {
        return switch (light.getType()) {
            case DIRECTIONAL -> shadows.cascades();
            case SPOT -> 1;
            case POINT -> 6;
        };
    }

    /**
     * The lights that will cast shadows, at most {@link Scene3D#MAX_SHADOW_LIGHTS}, directional ones first and then the
     * nearest to the camera. A point or spot light whose reach is wholly outside the camera's view is left out, since nothing
     * it lights can be seen.
     *
     * @param cameraPlanes the camera's view volume
     * @param truncated    {@code truncated[0]} is set when more lights wanted shadows than the limit allows
     */
    static List<Candidate> select(Scene3D scene, Vec3 eye, float[] cameraPlanes, boolean[] truncated) {
        return select(scene, eye, cameraPlanes, truncated, Scene3D.MAX_SHADOW_LIGHTS);
    }

    static List<Candidate> select(Scene3D scene, List<Light> lights, Vec3 eye, float[] cameraPlanes, boolean[] truncated) {
        return select(scene, lights, eye, cameraPlanes, truncated, Scene3D.MAX_SHADOW_LIGHTS);
    }

    /**
     * As above with the limit given, to try the choosing with fewer lights than the scene allows.
     */
    static List<Candidate> select(Scene3D scene, Vec3 eye, float[] cameraPlanes, boolean[] truncated, int limit) {
        return select(scene, scene.lights(), eye, cameraPlanes, truncated, limit);
    }

    /**
     * As above for the lights the frame shades (see {@link LightBudget}), whose positions in the list are the lights' numbers
     * in the shader.
     */
    static List<Candidate> select(Scene3D scene, List<Light> lights, Vec3 eye, float[] cameraPlanes, boolean[] truncated, int limit) {
        List<Candidate> wanted = new ArrayList<>();
        for (int i = 0; i < Math.min(lights.size(), Scene3D.MAX_LIGHTS); i++) {
            Light light = lights.get(i);
            Shadows s = light.getShadows();
            if (s == null || light.getIntensity() <= 0f) continue;
            if (light.getType() != Light.Type.DIRECTIONAL) {
                Vec3 p = light.getVector();
                if (!intersects(cameraPlanes, p.x(), p.y(), p.z(), light.getRange())) continue;
            }
            wanted.add(new Candidate(light, i, s, s.resolveSize(light.getType(), scene)));
        }
        wanted.sort(Comparator.comparingDouble(c -> c.light().getType() == Light.Type.DIRECTIONAL ? -1.0
                : c.light().getVector().sub(eye).length()));
        if (wanted.size() > limit) {
            truncated[0] = true;
            return new ArrayList<>(wanted.subList(0, limit));
        }
        return wanted;
    }

    /**
     * Works out a light's views once its tile size is settled.
     *
     * @param casters spheres {@code {x, y, z, radius}} around everything that casts shadows, to know how far toward a directional light they reach
     */
    static LightPlan plan(Scene3D scene, Camera camera, float aspect, Candidate c, int size, List<float[]> casters) {
        Light light = c.light();
        Shadows sh = c.shadows();
        float bias = sh.resolveBias(scene);
        List<View> views = new ArrayList<>();
        float[] splits = new float[4];
        switch (light.getType()) {
            case DIRECTIONAL -> {
                int n = sh.cascades();
                float near = camera.getNear();
                float far = Math.min(camera.getFar(), Math.max(near + 0.01f, sh.resolveDistance(scene)));
                float previous = near;
                for (int k = 0; k < n; k++) {
                    float split = k == n - 1 ? far : cascadeSplit(near, far, k + 1, n);
                    views.add(directional(camera, aspect, light, size, previous, split, bias, casters));
                    splits[k] = split;
                    previous = split;
                }
            }
            case SPOT -> views.add(spot(light, size, bias));
            case POINT -> views.addAll(point(light, size, bias));
        }
        return new LightPlan(light, c.index(), sh, size, views, splits);
    }

    /**
     * Where cascade {@code k} of {@code n} ends: halfway between an even split of the range and a logarithmic one, which keeps
     * the cascades near the camera small, where detail matters most.
     */
    static float cascadeSplit(float near, float far, int k, int n) {
        float t = (float) k / n;
        float log = near * (float) Math.pow(far / near, t);
        float even = near + (far - near) * t;
        return 0.7f * log + 0.3f * even;
    }

    /**
     * The orthographic view down the light over a sphere around a slice of the camera's view frustum. The sphere does not
     * change when the camera turns, and its centre moves in whole texels, so shadows don't shimmer as the camera moves.
     */
    static View directional(Camera camera, float aspect, Light light, int size, float near, float far, float bias, List<float[]> casters) {
        Vec3 dir = light.getVector().normalized();
        Vec3 eye = camera.getPosition();
        Vec3 f = camera.getTarget().sub(eye).normalized();
        Vec3 r = f.cross(camera.getUp()).normalized();
        Vec3 u = r.cross(f);
        float tanY = (float) Math.tan(Math.toRadians(camera.getFovYDegrees()) * 0.5);
        float tanX = tanY * aspect;

        Vec3 centre = Vec3.ZERO;
        Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            float d = (i & 1) == 0 ? near : far;
            float sx = (i & 2) == 0 ? -1 : 1, sy = (i & 4) == 0 ? -1 : 1;
            corners[i] = eye.add(f.mul(d)).add(r.mul(sx * tanX * d)).add(u.mul(sy * tanY * d));
            centre = centre.add(corners[i]);
        }
        centre = centre.mul(1f / 8f);
        float radius = 0f;
        for (Vec3 corner : corners) radius = Math.max(radius, corner.sub(centre).length());
        radius = Math.max(radius, 0.5f);

        // The light's own axes, to snap the centre to the shadow map's texel grid.
        Vec3 ref = Math.abs(dir.y()) > 0.99f ? Vec3.X : Vec3.UP;
        Vec3 lr = dir.cross(ref).normalized();
        Vec3 lu = lr.cross(dir);
        float texel = 2f * radius / size;
        float sx = (float) Math.floor(centre.dot(lr) / texel) * texel;
        float sy = (float) Math.floor(centre.dot(lu) / texel) * texel;
        centre = lr.mul(sx).add(lu.mul(sy)).add(dir.mul(centre.dot(dir)));

        // How far toward the light things can be and still need to cast into the picture.
        float back = radius;
        for (float[] s : casters) {
            back = Math.max(back, (centre.x() - s[0]) * dir.x() + (centre.y() - s[1]) * dir.y() + (centre.z() - s[2]) * dir.z() + s[3]);
        }
        back = Math.min(back, 2000f) + 1f;
        Vec3 lightEye = centre.sub(dir.mul(back));
        Mat4 view = Mat4.lookAt(lightEye, centre, ref);
        Mat4 proj = Mat4.orthographic(-radius, radius, -radius, radius, 0f, back + radius);
        return new View(proj.mul(view), false, 0f, back + radius, texel * 1.5f * bias, (texel * 2.5f * bias) / (back + radius));
    }

    /**
     * The perspective view of a spot light's cone. The map stores the distance along the axis, so the offsets grow with the
     * distance from the light, as the texels do.
     */
    static View spot(Light light, int size, float bias) {
        Vec3 position = light.getVector();
        Vec3 dir = light.getDirection();
        Vec3 ref = Math.abs(dir.y()) > 0.99f ? Vec3.X : Vec3.UP;
        float far = light.getRange();
        float near = Math.max(0.05f, far * 0.005f);
        float half = (float) Math.toRadians(light.getOuterAngle());
        Mat4 view = Mat4.lookAt(position, position.add(dir), ref);
        Mat4 proj = Mat4.perspective(Math.min(half * 2f + 0.05f, 3.0f), 1f, near, far);
        // The width of one texel at distance 1 along the axis.
        float texel = 2f * (float) Math.tan(Math.min(half + 0.025f, 1.5f)) / size;
        return new View(proj.mul(view), true, near, far, texel * 1.5f * bias, texel * 2.5f * bias / (far - near));
    }

    private static final Vec3[] FACE_DIRECTIONS = {
            new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)};
    private static final Vec3[] FACE_UPS = {Vec3.UP, Vec3.UP, Vec3.Z, Vec3.Z, Vec3.UP, Vec3.UP};

    /**
     * The half-angle of a point light's face views: a little over 45 degrees, so that the soft edge of a lookup near the seam
     * between two faces still finds its neighbours inside the face's own tile.
     */
    static final float FACE_HALF_ANGLE = (float) Math.atan(1.03);

    /**
     * Six square views around a point light, one along each axis, in the order +X, -X, +Y, -Y, +Z, -Z.
     */
    static List<View> point(Light light, int size, float bias) {
        Vec3 position = light.getVector();
        float far = light.getRange();
        float near = Math.max(0.05f, far * 0.005f);
        Mat4 proj = Mat4.perspective(FACE_HALF_ANGLE * 2f, 1f, near, far);
        float texel = 2f * (float) Math.tan(FACE_HALF_ANGLE) / size;
        List<View> faces = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            Mat4 view = Mat4.lookAt(position, position.add(FACE_DIRECTIONS[i]), FACE_UPS[i]);
            faces.add(new View(proj.mul(view), true, near, far, texel * 1.5f * bias, texel * 2.5f * bias / (far - near)));
        }
        return faces;
    }

    /**
     * Whether a sphere reaches into a view volume given as planes with inward normals.
     */
    static boolean intersects(float[] planes, float x, float y, float z, float r) {
        for (int p = 0; p < 6; p++) {
            if (planes[p * 4] * x + planes[p * 4 + 1] * y + planes[p * 4 + 2] * z + planes[p * 4 + 3] < -r) return false;
        }
        return true;
    }
}
