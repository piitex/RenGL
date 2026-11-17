package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Instances;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.LodSettings;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.OrbitControls;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Levels of detail on a wooded hillside: thousands of trees and rocks, each a mesh of thousands of triangles, drawn with a
 * simpler level the smaller they are on screen and, far off, as picture cards.
 * <ul>
 *   <li><b>Levels made for you.</b> Every mesh gets its levels from {@link Mesh#generateLods(LodSettings)}: meshoptimizer
 *       takes away triangles while keeping the shape, and says how far each level is off, so the scene knows the distance at
 *       which the change is under a pixel.</li>
 *   <li><b>Pixel error and bias.</b> The sliders trade quality for triangles: the error is how many pixels a level may be
 *       off before a more detailed one is drawn ({@link Scene3D#setLodPixelError}), the bias makes every mesh change sooner
 *       or later ({@link Scene3D#setLodBias}). Watch the triangle count.</li>
 *   <li><b>Colours by level.</b> Shows which level each tree draws: white the full mesh, then green, yellow, orange, red;
 *       the picture cards are the last colour.</li>
 *   <li><b>Fade.</b> With it on, a tree changing level draws both for a quarter of a second, in a scattered pattern, so it
 *       does not pop ({@link Scene3D.LodTransition#DITHER}).</li>
 *   <li><b>Picture cards.</b> Far trees and rocks become a card facing the camera with pictures of the mesh from 64
 *       directions, lit like the mesh ({@link Mesh#setImpostor}): two triangles each.</li>
 *   <li><b>Simpler shadows.</b> Shadows drawn one level simpler than the picture ({@link Scene3D#setShadowLodBias}).</li>
 *   <li><b>Virtual geometry.</b> Vulkan only: the meshes are also split into clusters ({@link Mesh#buildClusters}), and the
 *       GPU picks the detail of every part of a mesh each frame ({@link Scene3D#setVirtualGeometry}): the big rock face is
 *       fine where it is near and coarse where it turns away, with no level to change. "Hide what is hidden" leaves out the
 *       clusters the trees in front cover ({@link Scene3D#setVirtualGeometryOcclusion}). A tree with levels draws clusters only
 *       while it would need its full mesh, which takes being very close; the big rock face, which has clusters and no
 *       levels, always draws them. Run with {@code -Drengl.demo.api=VULKAN} on Windows and Linux.</li>
 * </ul>
 * Drag to orbit and scroll to zoom. Like the other 3D demos it asks for Vulkan on macOS and OpenGL elsewhere;
 * {@code -Drengl.demo.api=OPENGL} chooses.
 */
public class LodDemo {
    private static final int TREES = 3000, ROCKS = 1200;
    private static final float SPREAD = 220f;

    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("Levels of detail").setDimensions(1280, 720).setSplashEnabled(false).setRenderer(RenderAPI.VULKAN).setVsync(false).setMaxFPS(1000));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1180, 720);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        Forest forest = new Forest();
        forest.window = window;

        TextOverlay title = new TextOverlay("Levels of detail", 24f, Color.WHITE);
        title.setPosition(24, 18);
        root.addElement(title);

        Viewport3D view = new Viewport3D(820, 680);
        view.setPosition(340, 20);
        view.getStyling().setCornerRadius(12f);
        view.setContent(forest.scene);
        view.setInputListener(forest.orbit);
        root.addElement(view);

        TextOverlay hint = new TextOverlay("Drag to orbit, scroll to zoom", 13f, new Color(1f, 1f, 1f, 0.55f));
        hint.setPosition(356, 684);
        root.addElement(hint);

        float y = 64;
        TextOverlay errorLabel = new TextOverlay("Pixel error: 1.0", 14f, Color.WHITE);
        errorLabel.setPosition(24, y);
        root.addElement(errorLabel);
        SliderOverlay error = new SliderOverlay(284, 0.25, 8, 1);
        error.setPosition(24, y + 24);
        error.onValueChange(value -> {
            forest.scene.setLodPixelError(value.floatValue());
            errorLabel.setText(String.format("Pixel error: %.2f", value));
        });
        root.addElement(error);
        y += 62;
        TextOverlay biasLabel = new TextOverlay("Bias: 0.0", 14f, Color.WHITE);
        biasLabel.setPosition(24, y);
        root.addElement(biasLabel);
        SliderOverlay bias = new SliderOverlay(284, -2, 3, 0);
        bias.setPosition(24, y + 24);
        bias.onValueChange(value -> {
            forest.scene.setLodBias(value.floatValue());
            biasLabel.setText(String.format("Bias: %.2f", value));
        });
        root.addElement(bias);
        y += 70;
        y = checkBox(root, "Levels of detail", y, true, forest.scene::setLodEnabled);
        y = checkBox(root, "Colours by level", y, false, forest.scene::setLodDebugColors);
        y = checkBox(root, "Fade between levels", y, true,
                on -> forest.scene.setLodTransition(on ? Scene3D.LodTransition.DITHER : Scene3D.LodTransition.NONE));
        y = checkBox(root, "Picture cards far off", y, true, forest::setCards);
        y = checkBox(root, "Simpler shadows", y, true, on -> forest.scene.setShadowLodBias(on ? 1 : 0));
        y = checkBox(root, "Virtual geometry (Vulkan)", y, false, forest.scene::setVirtualGeometry);
        y = checkBox(root, "Hide what is hidden", y, false, forest.scene::setVirtualGeometryOcclusion);

        TextOverlay[] stats = new TextOverlay[6];
        y -= 10;
        for (int i = 0; i < stats.length; i++) {
            stats[i] = new TextOverlay("", 14f, i == 0 ? Color.WHITE : new Color(1f, 1f, 1f, 0.8f));
            stats[i].setPosition(24, y + 16 + i * 22);
            root.addElement(stats[i]);
        }
        forest.onStats = text -> {
            String[] parts = text.split(" \\| ");
            for (int i = 0; i < stats.length && i < parts.length; i++) stats[i].setText(parts[i]);
        };

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
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
     * The hillside: a ground plane, and the trees (a trunk and a lumpy crown each) and rocks as three instanced nodes.
     */
    private static final class Forest {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final Mesh crown, trunk, rock;
        Consumer<String> onStats = _ -> {
        };
        Window window;
        private String shown = "";

        Forest() {
            scene.setBackground(new Color(0.55f, 0.7f, 0.9f, 1f));
            scene.setAmbient(new Color(0.75f, 0.85f, 1f, 1f), 0.35f);
            scene.lights().clear();
            scene.lights().add(Light.directional(new Vec3(-0.5f, -1f, -0.35f), new Color(1f, 0.95f, 0.85f, 1f), 1.15f).setCastShadow(true));
            scene.setShadowDistance(90f);
            scene.camera().setClip(0.2f, 600f);
            scene.setUpdateHandler((s, dt) -> updateStats());

            scene.add(new Node3D("ground", Mesh.plane(SPREAD * 2.4f, SPREAD * 2.4f), new Material(new Color(0.36f, 0.48f, 0.25f, 1f))));

            // Lumpy, so a simpler level really does look different: about 9,000 triangles a crown, 4,600 a rock.
            crown = lumpy(1f, 96, 48, 0.18f, 3);
            trunk = Mesh.cylinder(0.18f, 2.2f, 24);
            rock = lumpy(1f, 64, 36, 0.3f, 11);
            LodSettings settings = new LodSettings().impostor(true);
            crown.generateLods(settings);
            rock.generateLods(settings);
            trunk.generateLods(new LodSettings().minTriangles(8));
            crown.buildClusters();
            rock.buildClusters();
            trunk.buildClusters();

            Random random = new Random(7);
            Instances crowns = new Instances(), trunks = new Instances(), rocks = new Instances();
            for (int i = 0; i < TREES; i++) {
                float x = (random.nextFloat() * 2f - 1f) * SPREAD, z = (random.nextFloat() * 2f - 1f) * SPREAD;
                float size = 0.8f + random.nextFloat() * 0.7f;
                Quat turn = Quat.fromAxisAngle(Vec3.UP, random.nextFloat() * 6.28f);
                trunks.add(new Vec3(x, 1.1f * size, z), turn, new Vec3(size, size, size));
                crowns.add(new Vec3(x, 3.0f * size, z), turn, new Vec3(1.3f * size, 1.5f * size, 1.3f * size));
            }
            for (int i = 0; i < ROCKS; i++) {
                float x = (random.nextFloat() * 2f - 1f) * SPREAD, z = (random.nextFloat() * 2f - 1f) * SPREAD;
                float size = 0.4f + random.nextFloat() * 1.1f;
                rocks.add(new Vec3(x, size * 0.35f, z), Quat.fromAxisAngle(Vec3.UP, random.nextFloat() * 6.28f), new Vec3(size, size * 0.7f, size));
            }
            scene.add(new Node3D("crowns", crown, new Material(new Color(0.22f, 0.52f, 0.2f, 1f))).setInstances(crowns));
            scene.add(new Node3D("trunks", trunk, new Material(new Color(0.42f, 0.29f, 0.18f, 1f))).setInstances(trunks));
            scene.add(new Node3D("rocks", rock, new Material(new Color(0.52f, 0.5f, 0.47f, 1f))).setInstances(rocks));
            // One big rock face of about 65,000 triangles with clusters and no classic levels: what virtual geometry is for.
            // Without it the whole mesh is drawn; with it, fine where it is near and coarse where it turns away.
            Mesh cliff = lumpy(1f, 256, 128, 0.25f, 5);
            cliff.buildClusters();
            Node3D face = new Node3D("cliff", cliff, new Material(new Color(0.55f, 0.52f, 0.48f, 1f))).setPosition(-26f, 4f, -22f);
            face.setScale(new Vec3(16f, 10f, 12f));
            scene.add(face);

            orbit.setTarget(new Vec3(0, 2, 0)).setDistance(40f).setAngles(0.5f, 0.18f).setDistanceLimits(6f, 380f);
        }

        void setCards(boolean on) {
            crown.setImpostor(on);
            rock.setImpostor(on);
        }

        /**
         * A sphere pushed in and out by a few waves, smooth-shaded: every vertex at its own distance from the middle.
         */
        private static Mesh lumpy(float radius, int segments, int rings, float amount, int seed) {
            Mesh sphere = Mesh.sphere(radius, segments, rings);
            float[] v = sphere.vertices();
            int n = sphere.vertexCount();
            float[] positions = new float[n * 3], uvs = new float[n * 2];
            Random random = new Random(seed);
            float[][] waves = new float[6][4];
            for (float[] w : waves) {
                w[0] = random.nextFloat() * 2f - 1f;
                w[1] = random.nextFloat() * 2f - 1f;
                w[2] = random.nextFloat() * 2f - 1f;
                w[3] = 2f + random.nextFloat() * 5f;
            }
            for (int i = 0; i < n; i++) {
                float x = v[i * 8], y = v[i * 8 + 1], z = v[i * 8 + 2];
                float bump = 0f;
                for (float[] w : waves) bump += (float) Math.sin((x * w[0] + y * w[1] + z * w[2]) * w[3]);
                float scale = 1f + amount * bump / waves.length;
                positions[i * 3] = x * scale;
                positions[i * 3 + 1] = y * scale;
                positions[i * 3 + 2] = z * scale;
                uvs[i * 2] = v[i * 8 + 6];
                uvs[i * 2 + 1] = v[i * 8 + 7];
            }
            return new Mesh(positions, null, uvs, sphere.indices());
        }

        private void updateStats() {
            Scene3D.Stats s = scene.getStats();
            StringBuilder levels = new StringBuilder();
            for (int i = 0; i < Scene3D.Stats.LEVELS; i++) {
                int count = s.recordsAtLevel(i);
                if (count == 0) continue;
                if (!levels.isEmpty()) levels.append(", ");
                levels.append(i).append(": ").append(count);
            }
            String text = String.format("Triangles drawn: %,d | Records by level: %s | Drawn: %,d records, %d draw calls | "
                            + "Left out: %,d | Picture cards are level %d%s | Frames per second: %.0f",
                    s.triangles(), levels, s.instances(), s.drawCalls(), s.culled(), crown.getLods() == null ? 0 : crown.getLods().size(),
                    scene.isUsingVirtualGeometry() ? ", some from clusters" : scene.isVirtualGeometry() ? ", none near enough for clusters" : "",
                    window == null ? 0.0 : window.getFps());
            if (!text.equals(shown)) {
                shown = text;
                onStats.accept(text);
            }
        }
    }
}
