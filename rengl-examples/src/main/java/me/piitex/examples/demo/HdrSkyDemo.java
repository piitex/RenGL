package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.HdrTextureData;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.OrbitControls;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.ShadowQuality;
import me.piitex.engine.render3d.scene.Shadows;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.render3d.scene.ToneMapping;
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
import java.util.function.Consumer;

/**
 * A sky made of real light: {@link Skybox#equirectHdr} with a Radiance {@code .hdr} panorama.
 * <ul>
 *   <li>The sky is the background, lights the spheres (the matte ones from the whole sky at once, the shiny ones with its
 *       reflection), and its sun is thousands of times brighter than white, so it still shines in the chrome ball after
 *       tone mapping. Untick "Tone mapping" to see what an 8-bit sky would do: the sun and the clouds around it clip to
 *       flat white.</li>
 *   <li>The panorama can't cast shadows, so a directional light points where {@link HdrTextureData#sunDirection()} says the
 *       sun is, and that one does.</li>
 *   <li>With nothing chosen the demo makes a clear sky with a sun itself, so it runs without any file. "Load sky..." opens
 *       any {@code .hdr} file (free ones, such as the HDRIs at polyhaven.com, are a good start), and so does
 *       {@code -Drengl.demo.sky=path/to/sky.hdr}. The file is read on a background thread and swapped in between frames.</li>
 *   <li>Drag to orbit and scroll to zoom.</li>
 * </ul>
 * Like {@link Scene3DDemo} it asks for Vulkan on macOS and OpenGL elsewhere; {@code -Drengl.demo.api=VULKAN|OPENGL} chooses.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public class HdrSkyDemo {
    private static final Shadows SUN_SHADOWS = Shadows.of(ShadowQuality.HIGH).cascades(2).distance(30f);

    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("HDR sky demo").setDimensions(1100, 700).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1100, 700);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        Stage stage = new Stage();
        buildUi(root, stage);
        String path = System.getProperty("rengl.demo.sky");
        if (path != null) stage.loadInBackground(Path.of(path));

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    private static void buildUi(Container root, Stage stage) {
        TextOverlay title = new TextOverlay("HDR sky", 24f, Color.WHITE);
        title.setPosition(24, 18);
        root.addElement(title);

        Viewport3D view = new Viewport3D(760, 660);
        view.setPosition(320, 20);
        view.getStyling().setCornerRadius(12f);
        view.setContent(stage.scene);
        view.setInputListener(stage.orbit);
        root.addElement(view);

        TextOverlay hint = new TextOverlay("Drag to orbit, scroll to zoom", 13f, new Color(1f, 1f, 1f, 0.7f));
        hint.setPosition(336, 664);
        root.addElement(hint);

        TextOverlay status = new TextOverlay("Sky: made by the demo", 13f, new Color(1f, 1f, 1f, 0.8f));
        status.setPosition(24, 600);
        root.addElement(status);
        stage.onStatus = status::setText;

        float y = 66;
        y = slider(root, "Exposure", y, 0.1, 3, 0.8, value -> stage.scene.setExposure((float) (double) value));
        y = slider(root, "Sky lighting", y, 0, 2, 1, value -> stage.scene.setEnvironmentLighting((float) (double) value));
        y = slider(root, "Bloom", y, 0, 1, 0.2, value -> stage.scene.setBloom((float) (double) value));
        y = slider(root, "Sun light (shadows)", y, 0, 3, 1.5, value -> stage.sun.setIntensity((float) (double) value));

        y = checkBox(root, "Tone mapping (ACES)", y, true,
                on -> stage.scene.setToneMapping(on ? ToneMapping.ACES : ToneMapping.NONE));
        y = checkBox(root, "Sun shadows", y, true, on -> stage.sun.setShadows(on ? SUN_SHADOWS : null));

        ButtonOverlay load = new ButtonOverlay("Load sky...", 126, 32);
        load.setPosition(24, y + 6);
        load.onAction(() -> FileDialog.open()
                .filter("HDR panoramas", "hdr")
                .onSelect(stage::loadInBackground)
                .onError(status::setText)
                .show());
        root.addElement(load);

        ButtonOverlay generated = new ButtonOverlay("Demo sky", 126, 32);
        generated.setPosition(162, y + 6);
        generated.onAction(() -> stage.use(generateSky(), "Sky: made by the demo"));
        root.addElement(generated);
    }

    private static float slider(Container root, String label, float y, double min, double max, double value, Consumer<Double> onChange) {
        TextOverlay text = new TextOverlay(label, 14f, Color.WHITE);
        text.setPosition(24, y);
        root.addElement(text);
        SliderOverlay slider = new SliderOverlay(264, min, max, value);
        slider.setPosition(24, y + 22);
        slider.onValueChange(onChange);
        root.addElement(slider);
        return y + 56;
    }

    private static float checkBox(Container root, String label, float y, boolean checked, Consumer<Boolean> onToggle) {
        CheckBoxOverlay box = new CheckBoxOverlay(20, checked);
        box.setPosition(24, y);
        box.onToggle(onToggle);
        root.addElement(box);
        TextOverlay text = new TextOverlay(label, 14f, Color.WHITE);
        text.setPosition(54, y + 1);
        root.addElement(text);
        return y + 34;
    }

    /**
     * A clear sky with a sun, as floats: blue overhead fading to a pale horizon, a dim ground below it, a bright glow around
     * the sun and the sun itself, a small disc 4000 times brighter than white, which is what no 8-bit picture can hold.
     */
    static HdrTextureData generateSky() {
        int w = 1024, h = 512;
        Vec3 sun = new Vec3(0.55f, 0.42f, -0.72f).normalized();
        float[] rgba = new float[w * h * 4];
        for (int y = 0; y < h; y++) {
            double elevation = (0.5 - (y + 0.5) / h) * Math.PI;
            for (int x = 0; x < w; x++) {
                double phi = ((x + 0.5) / w - 0.5) * 2 * Math.PI;
                Vec3 dir = new Vec3((float) (Math.sin(phi) * Math.cos(elevation)), (float) Math.sin(elevation),
                        (float) (-Math.cos(phi) * Math.cos(elevation)));
                float r, g, b;
                if (elevation >= 0) {
                    float t = (float) Math.pow(Math.sin(elevation), 0.5);
                    r = 0.75f + (0.12f - 0.75f) * t;
                    g = 0.85f + (0.32f - 0.85f) * t;
                    b = 1.0f + (0.75f - 1.0f) * t;
                } else {
                    r = 0.18f;
                    g = 0.16f;
                    b = 0.14f;
                }
                float cos = Math.clamp(dir.x() * sun.x() + dir.y() * sun.y() + dir.z() * sun.z(), -1f, 1f);
                float angle = (float) Math.acos(cos);
                float glow = (float) Math.exp(-angle / 0.12) * 1.2f + (float) Math.exp(-angle / 0.5) * 0.25f;
                r += glow;
                g += glow * 0.9f;
                b += glow * 0.7f;
                if (angle < 0.045f) {
                    r = 4000f;
                    g = 3800f;
                    b = 3300f;
                }
                int i = (y * w + x) * 4;
                rgba[i] = r;
                rgba[i + 1] = g;
                rgba[i + 2] = b;
                rgba[i + 3] = 1f;
            }
        }
        return new HdrTextureData(w, h, rgba);
    }

    /**
     * The scene and what the UI changes. The overlays and the update handler run on the render thread; only
     * {@link #loadInBackground} starts another, and it hands its result over through {@link #pending}.
     */
    private static final class Stage {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final Light sun = Light.directional(new Vec3(0, -1, 0), new Color(1f, 0.96f, 0.88f, 1f), 1.5f).setShadows(SUN_SHADOWS);
        final AtomicReference<Loaded> pending = new AtomicReference<>();
        volatile Consumer<String> onStatus = text -> {
        };

        private record Loaded(HdrTextureData sky, String name) {
        }

        Stage() {
            scene.setToneMapping(ToneMapping.ACES);
            scene.setExposure(0.8f);
            scene.setEnvironmentLighting(1f);
            scene.setBloom(0.2f).setBloomThreshold(1f);
            scene.lights().add(sun);
            applySky(generateSky());

            scene.add(new Node3D("floor", Mesh.plane(14, 14), new Material(
                    TextureData.checker(256, 14, new float[]{0.62f, 0.62f, 0.62f}, new float[]{0.34f, 0.34f, 0.36f}))
                    .setPbr(true).setMetallic(0f).setRoughness(0.85f)));
            ball("chrome", -2.7f, new Color(0.95f, 0.95f, 0.97f, 1f), 1f, 0.04f);
            ball("gold", -0.9f, new Color(1f, 0.78f, 0.34f, 1f), 1f, 0.35f);
            ball("plastic", 0.9f, new Color(0.85f, 0.12f, 0.1f, 1f), 0f, 0.25f);
            ball("matte", 2.7f, Color.WHITE, 0f, 0.9f);

            orbit.setTarget(new Vec3(0, 0.7f, 0)).setDistance(8.5f).setAngles(0.3f, 0.25f).setDistanceLimits(3f, 14f);
            scene.setUpdateHandler(this::update);
        }

        private void ball(String name, float x, Color color, float metallic, float roughness) {
            Node3D ball = new Node3D(name, Mesh.sphere(0.8f, 64, 32), new Material(color).setPbr(true).setMetallic(metallic).setRoughness(roughness));
            ball.setPosition(x, 0.8f, 0);
            scene.add(ball);
        }

        /** The sky and a sun light pointing where the sky's own sun is. */
        private void applySky(HdrTextureData sky) {
            scene.setSkybox(Skybox.equirectHdr(sky));
            sun.setVector(sky.sunDirection().negate()); // a directional light keeps the way it travels in its vector
        }

        void use(HdrTextureData sky, String name) {
            pending.set(new Loaded(sky, name));
        }

        /** Reads a file on a new thread: an HDR picture touches no GPU until it is first drawn. */
        void loadInBackground(Path path) {
            Thread loader = new Thread(() -> {
                try {
                    use(HdrTextureData.load(path), "Sky: " + path.getFileName());
                } catch (IOException | RuntimeException e) {
                    onStatus.accept("Could not load " + path.getFileName() + ": " + e.getMessage());
                }
            }, "sky-loader");
            loader.setDaemon(true);
            loader.start();
        }

        private void update(Scene3D scene, float dt) {
            Loaded loaded = pending.getAndSet(null);
            if (loaded == null) return;
            applySky(loaded.sky());
            Vec3 d = loaded.sky().sunDirection();
            onStatus.accept(loaded.name() + " (" + loaded.sky().width() + " x " + loaded.sky().height() + ", sun "
                    + Math.round(Math.toDegrees(Math.asin(d.y()))) + " deg up)");
        }
    }
}
