package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.OrbitControls;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.ShadowQuality;
import me.piitex.engine.render3d.scene.Shadows;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;

/**
 * A product-shot studio for the optional 3D layer: a row of gold spheres and a row of colored ones, from rough to
 * mirror smooth, on a glossy floor under a sunset sky.
 * <ul>
 *   <li>The sky is a {@link Skybox#gradient gradient skybox}. It is also the image-based light, so the metal
 *       spheres reflect it.</li>
 *   <li>The sun casts cascaded shadows. Three small emissive spheres each carry a point light and orbit the rows,
 *       so the reflections move.</li>
 *   <li>The camera sways on its own until you touch the viewport. Drag to orbit and scroll to zoom
 *       ({@link OrbitControls}).</li>
 * </ul>
 * 3D needs an OpenGL or Vulkan window. The demo asks for Vulkan on macOS, where the default backend is Metal, and
 * OpenGL elsewhere. Start it with {@code -Drengl.demo.api=VULKAN} (or {@code OPENGL}) to choose.
 */
public class Studio3DDemo {
    private static final int COLUMNS = 7;
    private static final Color[] GLOW = {
            new Color(1f, 0.2f, 0.75f, 1f), new Color(0.2f, 0.85f, 1f, 1f), new Color(0.6f, 1f, 0.3f, 1f)};

    private static final Scene3D scene = new Scene3D();
    private static final Node3D[] orbs = new Node3D[GLOW.length];
    private static final Light[] bulbs = new Light[GLOW.length];
    private static float time;
    private static boolean swaying = true;

    static void main() {
        // -XstartOnFirstThread is required on macOS. To reduce console spam also add
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Window window = new Window(new WindowOptions("3D Studio Demo").setDimensions(1400, 800).setRenderer(chooseApi()));
        WindowOptions options = window.getWindowOptions();

        build();

        Container root = new Container(options.getWidth(), options.getHeight());
        root.getStyling().setBorderThickness(0f);
        root.onUpdate(e -> {
            root.setWidth(options.getWidth());
            root.setHeight(options.getHeight());
        });
        window.addContainer(root);

        Viewport3D viewport = new Viewport3D(options.getWidth(), options.getHeight());
        viewport.setContent(scene);
        // The first drag or scroll ends the sway. The controls start from wherever the camera is at that moment.
        viewport.setInputListener(new Viewport3D.InputListener() {
            private OrbitControls orbit;

            private OrbitControls orbit() {
                if (orbit == null) {
                    swaying = false;
                    orbit = new OrbitControls(scene.camera());
                }
                return orbit;
            }

            @Override
            public void dragged(double x, double y, double dx, double dy) {
                orbit().dragged(x, y, dx, dy);
            }

            @Override
            public void scrolled(double dx, double dy, double x, double y) {
                orbit().scrolled(dx, dy, x, y);
            }
        });
        viewport.onUpdate(e -> {
            viewport.setWidth(options.getWidth());
            viewport.setHeight(options.getHeight());
        });
        root.addElement(viewport);

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    private static void build() {
        scene.setBackground(new Color(0.02f, 0.02f, 0.05f, 1f));
        scene.setSkybox(Skybox.gradient(new Color(0.02f, 0.03f, 0.16f, 1f), new Color(1f, 0.5f, 0.42f, 1f),
                new Color(0.3f, 0.12f, 0.3f, 1f)));
        scene.setEnvironmentLighting(1.25f);
        scene.setFog(new Color(0.4f, 0.12f, 0.3f, 1f), 0.009f);

        Light sun = scene.lights().get(0);
        sun.setDirection(new Vec3(-0.55f, -0.45f, -0.7f)).setColor(new Color(1f, 0.7f, 0.5f, 1f)).setIntensity(2f);
        sun.setShadows(Shadows.of(ShadowQuality.HIGH).cascades(2).distance(30f));

        scene.add(new Node3D("floor", Mesh.plane(600, 600),
                new Material(new Color(0.05f, 0.05f, 0.07f, 1f)).setMetallic(0.6f).setRoughness(0.12f)));

        // Roughness grows left to right, so each row reads as a sample chart.
        for (int i = 0; i < COLUMNS; i++) {
            float x = -4.8f + i * 1.6f, rough = i / (float) (COLUMNS - 1);
            Node3D gold = new Node3D("gold" + i, Mesh.sphere(0.65f, 64, 32),
                    new Material(new Color(1f, 0.74f, 0.32f, 1f)).setMetallic(1f).setRoughness(0.04f + 0.9f * rough));
            gold.setPosition(x, 0.65f, -2f);
            scene.add(gold);

            Node3D paint = new Node3D("paint" + i, Mesh.sphere(0.65f, 64, 32),
                    new Material(hsv(0.92f - i * 0.13f, 0.75f, 0.95f)).setMetallic(0f).setRoughness(0.12f + 0.7f * rough));
            paint.setPosition(x, 0.65f, 1.6f);
            scene.add(paint);
        }

        for (int i = 0; i < GLOW.length; i++) {
            orbs[i] = new Node3D("orb" + i, Mesh.sphere(0.16f, 24, 12), new Material(Color.BLACK).setEmissive(GLOW[i], 4f));
            orbs[i].setCastShadow(false); // the light inside would be blocked by its own bulb
            scene.add(orbs[i]);
            bulbs[i] = Light.point(new Vec3(0, 1, 0), GLOW[i], 2.2f, 5.5f);
            scene.lights().add(bulbs[i]);
        }

        scene.setUpdateHandler((s, dt) -> animate(dt));
    }

    private static void animate(float dt) {
        time += dt;
        float a = time * 0.7f;
        if (swaying) {
            scene.camera().setFovYDegrees(36f)
                    .setPosition(new Vec3((float) Math.sin(a) * 3f, 6.6f + 0.4f * (float) Math.cos(a), 11f))
                    .setTarget(new Vec3(0, 0.1f, 0));
        }
        for (int i = 0; i < orbs.length; i++) {
            float p = a + i * 2.094f; // a third of a turn apart
            float x = (float) Math.sin(p) * 4.6f;
            float y = 0.55f + 0.35f * (float) Math.sin(p * 3);
            float z = (float) Math.cos(p * 2) * 1.2f + 0.1f;
            orbs[i].setPosition(x, y, z);
            bulbs[i].setVector(new Vec3(x, y + 0.2f, z));
        }
    }

    private static Color hsv(float h, float s, float v) {
        int i = (int) (h * 6) % 6;
        float f = h * 6 - (int) (h * 6), p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        float[][] c = {{v, t, p}, {q, v, p}, {p, v, t}, {p, q, v}, {t, p, v}, {v, p, q}};
        return new Color(c[i][0], c[i][1], c[i][2], 1f);
    }
}
