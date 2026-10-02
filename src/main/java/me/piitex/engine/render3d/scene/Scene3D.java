package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.RenderTarget;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 3D scene to show in a {@link Viewport3D}: a tree of {@link Node3D}s, a {@link Camera}, {@link Light}s and a
 * background. It is a {@link Viewport3D.Content}, so showing it is
 * <pre>
 * Scene3D scene = new Scene3D();
 * scene.add(new Node3D("box", Mesh.cube(1f), new Material(Color.CORNFLOWER_BLUE)));
 * Viewport3D view = new Viewport3D(640, 360);
 * view.setContent(scene);
 * view.setInputListener(new OrbitControls(scene.camera()));
 * container.addElement(view);
 * </pre>
 * and models come from {@link Model#load}. Everything is drawn with one forward renderer: classic (Lambert and
 * Blinn-Phong) or physically based shading with normal, metallic-roughness, occlusion and emissive maps, up to
 * {@link #MAX_LIGHTS} lights, {@link Light#setCastShadow shadows} from a directional or spot light, {@link #setFog fog}, a {@link Skybox},
 * mipmapped and anisotropically filtered textures, {@link Instances instanced} drawing, {@link Skin skinned} meshes
 * moved by {@link AnimationPlayer}s, alpha and additive blending, depth testing and back-face culling; the viewport anti-aliases the
 * edges. There is no image-based lighting or reflection. {@link #pick} finds what is under a point of the picture.
 * <p>
 * A new scene has a soft ambient light and one directional light, so the first thing you add is visible; clear
 * {@link #lights()} for different lighting.
 * <p>
 * The scene's data is plain Java and can be built on any thread, but change it while it is drawn only from the render
 * thread, such as in an {@link #setUpdateHandler update handler}. One scene can be shown by several viewports; it is
 * then drawn once per viewport per frame, and its update handler runs once per frame, before the first of them.
 */
public class Scene3D implements Viewport3D.Content {
    /**
     * The most lights drawn; the first ones in {@link #lights()}.
     */
    public static final int MAX_LIGHTS = 8;

    /**
     * Runs once per frame before the scene is drawn.
     */
    @FunctionalInterface
    public interface UpdateHandler {
        /**
         * @param delta seconds since the previous frame
         */
        void update(Scene3D scene, float delta);
    }

    private final Node3D root = new Node3D("root");
    private final Camera camera = new Camera();
    private final List<Light> lights = new ArrayList<>();
    private Color background = new Color(0.10f, 0.11f, 0.14f, 1f);
    private Color ambient = new Color(0.45f, 0.45f, 0.5f, 1f);
    private float ambientIntensity = 1f;
    private UpdateHandler updateHandler;
    private Skybox skybox;
    private int shadowMapSize = 2048;
    private float shadowDistance = 60f;
    private float shadowBias = 1f;
    private Color fogColor = Color.WHITE;
    private float fogDensity;
    private float fogHeightFalloff;
    private final List<AnimationPlayer> animators = new ArrayList<>();

    private final Map<GpuDevice, Shared> renderers = new IdentityHashMap<>();

    private static final class Shared {
        final SceneRenderer renderer;
        int users;
        long updatedFrame = -1;

        Shared(SceneRenderer renderer) {
            this.renderer = renderer;
        }
    }

    public Scene3D() {
        lights.add(Light.directional(new Vec3(-0.4f, -1f, -0.6f), Color.WHITE, 0.9f));
    }

    /**
     * The top of the node tree. Everything under it is drawn.
     */
    public Node3D root() {
        return root;
    }

    /**
     * Shorthand for {@code root().add(node)}.
     */
    public Scene3D add(Node3D node) {
        root.add(node);
        return this;
    }

    public Camera camera() {
        return camera;
    }

    /**
     * The scene's lights; add and remove from it directly.
     */
    public List<Light> lights() {
        return lights;
    }

    public Color getBackground() {
        return background;
    }

    public Scene3D setBackground(Color background) {
        this.background = background;
        return this;
    }

    public Color getAmbient() {
        return ambient;
    }

    public float getAmbientIntensity() {
        return ambientIntensity;
    }

    /**
     * The light that reaches every surface from every side, so nothing is pitch black. {@code intensity} 0 turns it off.
     */
    public Scene3D setAmbient(Color color, float intensity) {
        this.ambient = color;
        this.ambientIntensity = intensity;
        return this;
    }

    public Skybox getSkybox() {
        return skybox;
    }

    /**
     * The sky drawn behind everything, in place of the plain {@link #setBackground background colour}; {@code null}
     * (the default) draws just the colour.
     */
    public Scene3D setSkybox(Skybox skybox) {
        this.skybox = skybox;
        return this;
    }

    public int getShadowMapSize() {
        return shadowMapSize;
    }

    /**
     * The width and height in pixels of the shadow picture: more is sharper and costs more memory and time. 2048 by
     * default; from 256 to 8192.
     */
    public Scene3D setShadowMapSize(int size) {
        this.shadowMapSize = Math.max(256, Math.min(8192, size));
        return this;
    }

    public float getShadowDistance() {
        return shadowDistance;
    }

    /**
     * How far from the camera shadows are drawn, in scene units (60 by default, and never beyond the camera's far
     * plane). Shorter gives sharper shadows near the camera.
     */
    public Scene3D setShadowDistance(float distance) {
        this.shadowDistance = Math.max(0.1f, distance);
        return this;
    }

    public Color getFogColor() {
        return fogColor;
    }

    public float getFogDensity() {
        return fogDensity;
    }

    public float getFogHeightFalloff() {
        return fogHeightFalloff;
    }

    /**
     * Fades distant things into {@code color}. The strength is {@code 1 - exp(-(distance * density)^2)}: nothing close
     * up, thick far away. {@code 0.02} hazes things 40 units away; {@code 0} switches fog off (the default). Match the
     * colour to the {@link Skybox} at the horizon so the world melts into the sky. Fog applies to everything but
     * the skybox.
     */
    public Scene3D setFog(Color color, float density) {
        return setFog(color, density, 0f);
    }

    /**
     * Fog that is thicker low down, like mist over a meadow. {@code heightFalloff} is how fast it thins with height:
     * 0 is the same at every height, {@code 0.3} halves it about every 2.3 units up.
     */
    public Scene3D setFog(Color color, float density, float heightFalloff) {
        this.fogColor = color;
        this.fogDensity = Math.max(0f, density);
        this.fogHeightFalloff = Math.max(0f, heightFalloff);
        return this;
    }

    public float getShadowBias() {
        return shadowBias;
    }

    /**
     * Scales the offset that stops a surface from shadowing itself (1 by default). Raise it if a lit surface shows
     * stripes, lower it if shadows float away from what casts them.
     */
    public Scene3D setShadowBias(float bias) {
        this.shadowBias = Math.max(0f, bias);
        return this;
    }

    /**
     * Registers a player, which then advances once per frame, before the update handler.
     */
    public Scene3D animate(AnimationPlayer player) {
        if (!animators.contains(player)) animators.add(player);
        return this;
    }

    public Scene3D stopAnimating(AnimationPlayer player) {
        animators.remove(player);
        return this;
    }

    /**
     * The nearest thing the ray hits, or null. Only visible nodes with a mesh are tested, and a triangle that faces away
     * from the ray is skipped unless its material is double-sided, so a click goes through what you can't see. A node
     * is skipped when {@link Node3D#setPickable} is off.
     * <p>
     * Call it from the render thread or while the scene isn't drawing, as with any change to the scene. Each mesh keeps
     * a hierarchy of boxes (built the first time it is picked), so a ray tests only the triangles near its path. A
     * skinned mesh has no such hierarchy, because its shape changes with the pose: it is posed on the CPU and searched
     * afresh, which is fine for a click and not for thousands of rays per frame.
     */
    public Hit pick(Ray ray) {
        List<Hit> hits = pickAll(ray);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * What is under a point of the picture, using the camera: {@code pick(camera().screenRay(...))}, limited to what the
     * camera can see. Pass a viewport's mouse position and size, in points from its top-left corner:
     * <pre>
     * public void clicked(double x, double y) {
     *     Hit hit = scene.pick(x, y, view.getWidth(), view.getHeight());
     * }
     * </pre>
     */
    public Hit pick(double x, double y, double width, double height) {
        List<Hit> hits = new ArrayList<>();
        Picking.cast(root, Mat4.identity(), camera.screenRay(x, y, width, height), camera.getFar(), hits);
        hits.sort((a, b) -> Float.compare(a.distance(), b.distance()));
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * Everything the ray hits, one {@link Hit} per node (its nearest), nearest first. For picking through glass or
     * listing what is stacked under the mouse.
     */
    public List<Hit> pickAll(Ray ray) {
        List<Hit> hits = new ArrayList<>();
        Picking.cast(root, Mat4.identity(), ray, Float.POSITIVE_INFINITY, hits);
        hits.sort((a, b) -> Float.compare(a.distance(), b.distance()));
        return hits;
    }

    public Scene3D setUpdateHandler(UpdateHandler updateHandler) {
        this.updateHandler = updateHandler;
        return this;
    }

    @Override
    public void init(GpuDevice device) {
        Shared shared = renderers.get(device);
        if (shared == null) {
            shared = new Shared(new SceneRenderer(device));
            renderers.put(device, shared);
        }
        shared.users++;
    }

    @Override
    public void render(GpuDevice device, RenderTarget target, float delta) {
        Shared shared = renderers.get(device);
        if (shared == null) throw new IllegalStateException("The scene was drawn before it was initialised for this device");
        // Several viewports can show the scene in one frame; only the first runs the update.
        long frame = device.frameNumber();
        if (shared.updatedFrame != frame) {
            for (AnimationPlayer player : new ArrayList<>(animators)) player.update(delta);
            if (updateHandler != null) updateHandler.update(this, delta);
        }
        shared.updatedFrame = frame;
        shared.renderer.render(this, target);
    }

    @Override
    public void dispose(GpuDevice device) {
        Shared shared = renderers.get(device);
        if (shared != null && --shared.users <= 0) {
            renderers.remove(device);
            shared.renderer.close();
        }
    }
}
