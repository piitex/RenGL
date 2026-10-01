package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.RenderTarget;
import me.piitex.engine.render3d.Viewport3D;
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
 * and models come from {@link Model#load}. Everything is drawn with one forward renderer: Lambert and Blinn-Phong
 * lighting, textures, alpha blending, depth testing and back-face culling. There are no shadows, skybox,
 * anti-aliasing or animation yet.
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
        if (updateHandler != null && shared.updatedFrame != frame) updateHandler.update(this, delta);
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
