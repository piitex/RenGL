package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.LightFalloff;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Model;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.OrbitControls;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.ShadowQuality;
import me.piitex.engine.render3d.scene.Shadows;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The optional 3D layer inside an ordinary UI: {@link Viewport3D} elements sitting among overlays.
 * <ul>
 *   <li>The big viewport shows a {@link Scene3D}: a lit floor, a textured cube, a shiny sphere, a blended glass
 *       sphere, a model loaded with {@link Model#load}, and an orange point light circling them with a small unlit
 *       cube marking it. Drag to orbit and scroll to zoom: that is {@link OrbitControls}, attached with
 *       {@link Viewport3D#setInputListener}.</li>
 *   <li>The small viewport is a second, separate scene: one viewport per scene, any number per window.</li>
 *   <li>The sliders and check boxes are the usual overlays. They change the scene's lights and nodes directly.</li>
 *   <li>"Load model..." opens any glTF, GLB, OBJ, FBX or Collada file. It is read on a background thread, since
 *       {@link Model#load} touches no GPU, and the finished model is swapped in from the scene's update handler,
 *       which runs on the render thread between frames. Textures upload on the first frame they are drawn.</li>
 * </ul>
 * 3D needs an OpenGL or Vulkan window. The demo asks for Vulkan on macOS, where the default backend is Metal and a
 * {@code Viewport3D} would draw nothing, and OpenGL elsewhere. Start it with {@code -Drengl.demo.api=VULKAN} (or
 * {@code OPENGL}) to choose.
 */
public class Scene3DDemo {
    private static final String MODEL_DIR = "rengl-examples/src/main/resources/demo/3d/";
    private static final float SHOWCASE_SIZE = 2f; // a loaded model is scaled so its longest side is this long

    static void main() throws IOException {
        // -XstartOnFirstThread is required on macOS. To reduce console spam also add
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Window window = new Window(new WindowOptions("3D Demo").setDimensions(1100, 700).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1100, 700);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        Showcase showcase = new Showcase();
        buildUi(root, showcase);

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    private static void buildUi(Container root, Showcase showcase) {
        TextOverlay title = new TextOverlay("3D viewport", 24f, Color.WHITE);
        title.setPosition(24, 18);
        root.addElement(title);

        // The main view: orbit controls are a separate listener, so a Scene3D without them never takes the wheel.
        Viewport3D main = new Viewport3D(760, 660);
        main.setPosition(320, 20);
        main.getStyling().setCornerRadius(12f);
        main.setContent(showcase.scene);
        main.setInputListener(showcase.orbit);
        root.addElement(main);

        TextOverlay hint = new TextOverlay("Drag to orbit, scroll to zoom", 13f, new Color(1f, 1f, 1f, 0.55f));
        hint.setPosition(336, 664);
        root.addElement(hint);

        TextOverlay status = new TextOverlay("Showing: pyramid.obj", 13f, new Color(1f, 1f, 1f, 0.8f));
        status.setPosition(24, 600);
        root.addElement(status);
        showcase.onModelChanged = status::setText;

        TextOverlay shadowStats = new TextOverlay("", 13f, new Color(1f, 1f, 1f, 0.8f));
        shadowStats.setPosition(24, 622);
        root.addElement(shadowStats);
        showcase.onShadowStats = shadowStats::setText;

        float y = 66;
        y = slider(root, "Sun", y, 0, 2, 1, value -> showcase.sun().setIntensity((float) (double) value));
        y = slider(root, "Ambient", y, 0, 1, 0.12, value -> showcase.scene.setAmbient(Color.WHITE, (float) (double) value));

        y = checkBox(root, "Spin the model", y, true, on -> showcase.spin = on);
        y = checkBox(root, "Orbiting point light", y, true, on -> showcase.orbitingLight = on);
        y = checkBox(root, "Glass sphere", y, true, on -> showcase.glass.setVisible(on));
        y = checkBox(root, "Sun shadows (2 cascades)", y, true, on -> showcase.sun().setShadows(on ? Showcase.SUN_SHADOWS : null));
        y = checkBox(root, "Point light shadows", y, true, on -> showcase.orbiting.setShadows(on ? Showcase.LIGHT_SHADOWS : null));
        y = checkBox(root, "Linear light falloff", y, false,
                on -> showcase.orbiting.setFalloff(on ? LightFalloff.LINEAR : LightFalloff.WINDOWED_INVERSE_SQUARE));

        ButtonOverlay reset = new ButtonOverlay("Reset camera", 126, 32);
        reset.setPosition(24, y + 6);
        reset.onAction(showcase::resetCamera);
        root.addElement(reset);

        ButtonOverlay load = new ButtonOverlay("Load model...", 126, 32);
        load.setPosition(162, y + 6);
        load.onAction(() -> FileDialog.open()
                .filter("3D models", "gltf", "glb", "obj", "fbx", "dae")
                .onSelect(showcase::loadInBackground)
                .onError(status::setText)
                .show());
        root.addElement(load);

        // A second viewport, with its own scene, in the corner of the panel.
        Viewport3D preview = new Viewport3D(264, 150);
        preview.setPosition(24, 438);
        preview.getStyling().setCornerRadius(12f);
        preview.setContent(buildPreviewScene());
        root.addElement(preview);
    }

    private static float slider(Container root, String label, float y, double min, double max, double value,
                                java.util.function.Consumer<Double> onChange) {
        TextOverlay text = new TextOverlay(label, 14f, Color.WHITE);
        text.setPosition(24, y);
        root.addElement(text);
        SliderOverlay slider = new SliderOverlay(264, min, max, value);
        slider.setPosition(24, y + 22);
        slider.onValueChange(onChange);
        root.addElement(slider);
        return y + 56;
    }

    private static float checkBox(Container root, String label, float y, boolean checked,
                                  java.util.function.Consumer<Boolean> onToggle) {
        CheckBoxOverlay box = new CheckBoxOverlay(20, checked);
        box.setPosition(24, y);
        box.onToggle(onToggle);
        root.addElement(box);
        TextOverlay text = new TextOverlay(label, 14f, Color.WHITE);
        text.setPosition(54, y + 1);
        root.addElement(text);
        return y + 34;
    }

    private static Scene3D buildPreviewScene() {
        Scene3D scene = new Scene3D();
        scene.setBackground(new Color(0.07f, 0.08f, 0.12f, 1f));
        TextureData checker = TextureData.checker(128, 8, new float[]{0.95f, 0.95f, 0.95f}, new float[]{0.2f, 0.45f, 0.9f});
        Node3D ball = new Node3D("ball", Mesh.sphere(0.8f, 48, 24), new Material(checker).setSpecular(0.6f).setShininess(48));
        scene.add(ball);
        scene.camera().setPosition(new Vec3(0, 0.6f, 2.8f));
        scene.camera().setTarget(Vec3.ZERO);
        scene.setUpdateHandler((s, dt) -> ball.rotate(Vec3.UP, dt * 0.8f));
        return scene;
    }

    // ---- the main scene -------------------------------------------------------------------------

    /**
     * The big scene and the state the UI changes. The overlays and the scene's update handler both run on the
     * render thread; only {@link #loadInBackground} starts another, and it hands its result over through
     * {@link #pending}.
     */
    private static final class Showcase {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final Node3D holder = new Node3D("showcase"); // whatever model is on display is its only child
        final Node3D cube, glass, marker;
        // A sun with two cascades keeps shadows sharp near the camera and still reaches the edge of the floor; the orbiting bulb
        // shadows in every direction, from six pictures of the shared shadow atlas.
        static final Shadows SUN_SHADOWS = Shadows.of(ShadowQuality.HIGH).cascades(2).distance(30f);
        static final Shadows LIGHT_SHADOWS = Shadows.of(ShadowQuality.MEDIUM);
        final Light orbiting = Light.point(new Vec3(2, 1.6f, 0), new Color(1f, 0.6f, 0.2f, 1f), 1.4f, 7f).setShadows(LIGHT_SHADOWS);
        final AtomicReference<Model> pending = new AtomicReference<>();
        volatile boolean spin = true;
        volatile boolean orbitingLight = true;
        java.util.function.Consumer<String> onModelChanged = text -> {
        };
        java.util.function.Consumer<String> onShadowStats = text -> {
        };
        private String shownStats = "";
        private volatile String pendingName = "";
        private float time;

        Showcase() throws IOException {
            scene.setBackground(new Color(0.13f, 0.15f, 0.21f, 1f));
            scene.setAmbient(Color.WHITE, 0.12f);
            sun().setShadows(SUN_SHADOWS);
            scene.lights().add(orbiting);

            scene.add(new Node3D("floor", Mesh.plane(12, 12), new Material(
                    TextureData.checker(256, 12, new float[]{0.5f, 0.5f, 0.5f}, new float[]{0.28f, 0.28f, 0.3f})).setSpecular(0.05f)));

            TextureData crate = TextureData.load(Path.of(MODEL_DIR, "tex.png"));
            cube = new Node3D("cube", Mesh.cube(1f), new Material(crate));
            cube.setPosition(-2.2f, 0.5f, 0.6f);
            scene.add(cube);

            Node3D sphere = new Node3D("sphere", Mesh.sphere(0.55f, 48, 24),
                    new Material(new Color(0.9f, 0.15f, 0.15f, 1f)).setSpecular(0.8f).setShininess(64));
            sphere.setPosition(2.2f, 0.55f, -0.4f);
            scene.add(sphere);

            // Blended nodes are drawn after the solid ones, far to near, so the sphere behind shows through.
            glass = new Node3D("glass", Mesh.sphere(0.7f, 32, 16), new Material(new Color(0.3f, 0.55f, 1f, 0.4f)).setBlend(true));
            glass.setPosition(1.3f, 0.7f, 1.4f);
            scene.add(glass);

            marker = new Node3D("marker", Mesh.cube(0.18f), new Material(new Color(1f, 0.7f, 0.3f, 1f)).setUnlit(true));
            marker.setCastShadow(false); // it sits at the bulb, and would shade everything the bulb lights
            scene.add(marker);

            scene.add(holder);
            show(Model.load(Path.of(MODEL_DIR, "pyramid.obj")));

            resetCamera();
            scene.setUpdateHandler(this::update);
        }

        Light sun() {
            return scene.lights().get(0);
        }

        void resetCamera() {
            orbit.setTarget(new Vec3(0, 0.6f, 0)).setDistance(8f).setAngles(0.6f, 0.4f);
        }

        /**
         * Reads a model file on a new thread. The render thread picks the result up in {@link #update}.
         */
        void loadInBackground(Path path) {
            Thread loader = new Thread(() -> {
                try {
                    Model model = Model.load(path);
                    pendingName = path.getFileName().toString();
                    pending.set(model);
                } catch (IOException | RuntimeException e) {
                    onModelChanged.accept("Could not load " + path.getFileName() + ": " + e.getMessage());
                }
            }, "model-loader");
            loader.setDaemon(true);
            loader.start();
        }

        private void update(Scene3D scene, float dt) {
            Model loaded = pending.getAndSet(null);
            if (loaded != null) {
                show(loaded);
                onModelChanged.accept("Showing: " + pendingName);
            }

            time += dt;
            if (spin) holder.rotate(Vec3.UP, dt * 0.7f);
            cube.rotate(Vec3.UP, dt * 0.5f);

            if (orbitingLight) {
                orbiting.setVector(new Vec3((float) Math.cos(time) * 3f, 1.6f, (float) Math.sin(time) * 3f));
                orbiting.setIntensity(1.4f);
            } else {
                orbiting.setIntensity(0f);
            }
            marker.setVisible(orbitingLight);
            marker.setPosition(orbiting.getVector());

            Scene3D.Stats stats = scene.getStats(); // what the last frame drew
            String text = "Shadow pictures: " + stats.shadowViews() + " in a "
                    + stats.shadowAtlasWidth() + " x " + stats.shadowAtlasHeight() + " atlas";
            if (!text.equals(shownStats)) {
                shownStats = text;
                onShadowStats.accept(text);
            }
        }

        /**
         * Replaces the model on display, scaled to {@link #SHOWCASE_SIZE} and standing on the floor in the middle.
         */
        private void show(Model model) {
            for (Node3D old : new java.util.ArrayList<>(holder.getChildren())) holder.remove(old);

            Bounds bounds = model.bounds();
            Vec3 size = bounds.max().sub(bounds.min());
            float longest = Math.max(size.x(), Math.max(size.y(), size.z()));
            float scale = longest > 0f ? SHOWCASE_SIZE / longest : 1f;
            Vec3 center = bounds.center();

            Node3D root = model.root();
            root.setScale(scale);
            root.setPosition(-center.x() * scale, -bounds.min().y() * scale, -center.z() * scale);
            holder.add(root);
        }
    }
}
