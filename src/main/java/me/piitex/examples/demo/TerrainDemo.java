package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BodyType;
import me.piitex.engine.render3d.physics.BoxShape;
import me.piitex.engine.render3d.physics.ColliderView;
import me.piitex.engine.render3d.physics.ConvexHullShape;
import me.piitex.engine.render3d.physics.MeshShape;
import me.piitex.engine.render3d.physics.PhysicsMaterial;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.RaycastHit;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
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
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mesh and hull colliders: rocks tumble down a hilly terrain.
 * <ul>
 *   <li>The terrain is one {@link Mesh} of 7200 triangles. The same mesh is drawn and, as a {@link MeshShape} on a
 *       static body, is the ground.</li>
 *   <li>Each rock is a {@link ConvexHullShape} around a random cloud of points, drawn with {@link ConvexHullShape#toMesh()}
 *       so it looks exactly as it collides. Crates are boxes, for comparison.</li>
 *   <li>Click the terrain to drop a rock there. Drag to orbit and scroll to zoom.</li>
 * </ul>
 * Like the 3D demo it asks for Vulkan on macOS and OpenGL elsewhere; {@code -Drengl.demo.api=OPENGL} chooses.
 */
public class TerrainDemo {
    private static final int CELLS = 60;
    private static final float CELL = 0.5f;
    private static final Mesh CRATE = Mesh.cube(1f);
    private static final int MAX_BODIES = 15000;

    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("Terrain Demo").setDimensions(1100, 700).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1100, 700);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        Hills hills = new Hills();

        TextOverlay title = new TextOverlay("Terrain", 24f, Color.WHITE);
        title.setPosition(24, 18);
        root.addElement(title);

        Viewport3D view = new Viewport3D(760, 660);
        view.setPosition(320, 20);
        view.getStyling().setCornerRadius(12f);
        view.setContent(hills.scene);
        view.setInputListener(new Viewport3D.InputListener() {
            @Override
            public void dragged(double x, double y, double dx, double dy) {
                hills.orbit.dragged(x, y, dx, dy);
            }

            @Override
            public void scrolled(double dx, double dy, double x, double y) {
                hills.orbit.scrolled(dx, dy, x, y);
            }

            @Override
            public void clicked(double x, double y) {
                hills.dropAt(hills.scene.camera().screenRay(x, y, view.getWidth(), view.getHeight()));
            }
        });
        root.addElement(view);

        TextOverlay hint = new TextOverlay("Click the ground to drop a rock, drag to orbit, scroll to zoom", 13f, new Color(1f, 1f, 1f, 0.55f));
        hint.setPosition(336, 664);
        root.addElement(hint);

        TextOverlay stats = new TextOverlay("", 14f, Color.WHITE);
        stats.setPosition(24, 66);
        root.addElement(stats);
        hills.onStats = stats::setText;

        ButtonOverlay rocks = new ButtonOverlay("Drop 10 rocks", 264, 32);
        rocks.setPosition(24, 110);
        rocks.onAction(() -> hills.rain(10, false));
        root.addElement(rocks);

        ButtonOverlay crates = new ButtonOverlay("Drop 10 crates", 264, 32);
        crates.setPosition(24, 150);
        crates.onAction(() -> hills.rain(10, true));
        root.addElement(crates);

        ButtonOverlay clear = new ButtonOverlay("Clear", 264, 32);
        clear.setPosition(24, 190);
        clear.onAction(hills::clear);
        root.addElement(clear);

        CheckBoxOverlay slow = new CheckBoxOverlay(20, false);
        slow.setPosition(24, 238);
        slow.onToggle(on -> hills.speed = on ? 0.25f : 1f);
        root.addElement(slow);
        TextOverlay slowText = new TextOverlay("Slow motion", 14f, Color.WHITE);
        slowText.setPosition(54, 239);
        root.addElement(slowText);

        CheckBoxOverlay show = new CheckBoxOverlay(20, false);
        show.setPosition(24, 272);
        show.onToggle(hills.colliders::setVisible);
        root.addElement(show);
        TextOverlay showText = new TextOverlay("Show colliders", 14f, Color.WHITE);
        showText.setPosition(54, 273);
        root.addElement(showText);

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    /** The scene and its world. Everything here runs on the render thread. */
    private static final class Hills {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final PhysicsWorld world = new PhysicsWorld();
        final ColliderView colliders = new ColliderView(world).setVisible(false);
        final List<RigidBody> dropped = new ArrayList<>();
        final Random random = new Random(7);
        float speed = 1f;
        java.util.function.Consumer<String> onStats = _ -> {
        };
        private String shownStats = "";

        Hills() {
            scene.setBackground(new Color(0.55f, 0.7f, 0.85f, 1f));
            scene.setAmbient(Color.WHITE, 0.25f);
            scene.lights().getFirst().setShadows(Shadows.of(ShadowQuality.HIGH).cascades(3).distance(45f));
            scene.add(colliders.getNode());
            scene.setUpdateHandler((_, dt) -> {
                world.update(dt * speed);
                // Whatever rolls off the edge falls forever; let it go.
                for (RigidBody body : new ArrayList<>(dropped)) if (body.getPosition().y() < -20f) remove(body);
                updateStats();
            });

            Mesh ground = terrain();
            Node3D terrain = new Node3D("terrain", ground, new Material(
                    TextureData.checker(256, 16, new float[]{0.36f, 0.52f, 0.28f}, new float[]{0.31f, 0.46f, 0.24f})).setSpecular(0.05f));
            scene.add(terrain);
            world.add(new RigidBody(terrain, BodyType.STATIC).addShape(MeshShape.of(ground)).setMaterial(PhysicsMaterial.DEFAULT));

            orbit.setTarget(new Vec3(0, 0, 0)).setDistance(30f).setAngles(0.5f, 0.55f);
            rain(12, false);
        }

        /** Rolling hills around a hollow, highest at the back so things roll toward the camera. */
        private static float height(float x, float z) {
            double hills = 1.2 * Math.sin(x * 0.35) * Math.cos(z * 0.3) + 0.5 * Math.sin(x * 0.9 + z * 0.6);
            double hollow = -2.0 * Math.exp(-(x * x + z * z) / 30.0);
            double slope = -0.12 * z;
            return (float) (hills + hollow + slope);
        }

        private static Mesh terrain() {
            int n = CELLS + 1;
            float start = -CELLS * CELL * 0.5f;
            float[] positions = new float[n * n * 3];
            float[] uvs = new float[n * n * 2];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    float x = start + i * CELL, z = start + j * CELL;
                    int v = i * n + j;
                    positions[v * 3] = x;
                    positions[v * 3 + 1] = height(x, z);
                    positions[v * 3 + 2] = z;
                    uvs[v * 2] = i / (float) CELLS;
                    uvs[v * 2 + 1] = j / (float) CELLS;
                }
            }
            int[] indices = new int[CELLS * CELLS * 6];
            int k = 0;
            for (int i = 0; i < CELLS; i++) {
                for (int j = 0; j < CELLS; j++) {
                    int a = i * n + j, b = i * n + j + 1, c = (i + 1) * n + j + 1, d = (i + 1) * n + j;
                    indices[k++] = a;
                    indices[k++] = b;
                    indices[k++] = c;
                    indices[k++] = a;
                    indices[k++] = c;
                    indices[k++] = d;
                }
            }
            return new Mesh(positions, null, uvs, indices);
        }

        void rain(int count, boolean crates) {
            float half = CELLS * CELL * 0.4f;
            for (int i = 0; i < count; i++) {
                float x = (random.nextFloat() * 2f - 1f) * half, z = (random.nextFloat() * 2f - 1f) * half;
                Vec3 at = new Vec3(x, height(x, z) + 4f + random.nextFloat() * 6f, z);
                if (crates) crate(at);
                else rock(at);
            }
        }

        void dropAt(Ray ray) {
            RaycastHit hit = world.raycast(Ray.of(ray.origin(), ray.direction()), 200f);
            if (hit != null) rock(hit.point().add(new Vec3(0, 5f, 0)));
        }

        /** A rock: the hull around a squashed cloud of points, drawn from the hull itself. */
        private void rock(Vec3 at) {
            List<Vec3> points = new ArrayList<>();
            float size = 0.3f + random.nextFloat() * 0.5f;
            Vec3 stretch = new Vec3(1f + random.nextFloat() * 0.6f, 0.6f + random.nextFloat() * 0.4f, 1f + random.nextFloat() * 0.3f);
            for (int i = 0; i < 18; i++) {
                Vec3 p = new Vec3((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()).normalized();
                points.add(p.mul(stretch).mul(size * (0.8f + random.nextFloat() * 0.3f)));
            }
            ConvexHullShape hull = ConvexHullShape.of(points);
            float grey = 0.4f + random.nextFloat() * 0.25f;
            Node3D node = new Node3D("rock", hull.toMesh(), new Material(new Color(grey, grey * 0.95f, grey * 0.9f, 1f)).setSpecular(0.15f));
            add(node, at, new RigidBody(node).addShape(hull).setDensity(2500f).setFriction(0.8f));
        }

        private void crate(Vec3 at) {
            float size = 0.6f + random.nextFloat() * 0.5f;
            Node3D node = new Node3D("crate", CRATE, new Material(new Color(0.62f, 0.45f, 0.26f, 1f)).setSpecular(0.2f));
            node.setScale(size);
            add(node, at, new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setDensity(600f).setMaterial(PhysicsMaterial.WOOD));
        }

        private void add(Node3D node, Vec3 at, RigidBody body) {
            if (dropped.size() >= MAX_BODIES) remove(dropped.getFirst());
            node.setPosition(at);
            node.setRotation(Quat.fromEuler(random.nextFloat() * 6f, random.nextFloat() * 6f, random.nextFloat() * 6f));
            scene.add(node);
            world.add(body);
            dropped.add(body);
        }

        private void remove(RigidBody body) {
            world.remove(body);
            scene.root().remove(body.getNode());
            dropped.remove(body);
        }

        void clear() {
            for (RigidBody body : new ArrayList<>(dropped)) remove(body);
        }

        private void updateStats() {
            int awake = 0;
            for (RigidBody body : dropped) if (!body.isSleeping()) awake++;
            String text = dropped.size() + " bodies, " + awake + " awake";
            if (!text.equals(shownStats)) {
                shownStats = text;
                onStats.accept(text);
            }
        }
    }
}
