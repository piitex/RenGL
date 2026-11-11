package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.physx.PhysX;
import me.piitex.engine.physx.PhysXBackend;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BodyType;
import me.piitex.engine.render3d.physics.BoxShape;
import me.piitex.engine.render3d.physics.ColliderView;
import me.piitex.engine.render3d.physics.PhysicsMaterial;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.PlaneShape;
import me.piitex.engine.render3d.physics.RaycastHit;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.physics.SphereShape;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Rigid bodies in a {@link Scene3D}: a {@link PhysicsWorld} given to the scene with {@link Scene3D#setPhysics} moves the
 * nodes of its bodies every frame.
 * <ul>
 *   <li>A pyramid and a tower of crates stand on a floor (a static {@link PlaneShape}). They fall asleep once settled;
 *       the panel counts how many bodies are still awake.</li>
 *   <li>A ramp is a static box: one {@code Mesh.cube} node, stretched by the node's scale, which stretches its
 *       {@link BoxShape} the same way.</li>
 *   <li>Click to shoot a heavy rubber ball from the camera through the mouse: {@code camera().screenRay(...)} gives the
 *       direction. Drag to orbit and scroll to zoom, as in the 3D demo.</li>
 *   <li>Right of the panel, "What's under the mouse" is a {@link PhysicsWorld#raycast} along the same ray.</li>
 * </ul>
 * Like the 3D demo it asks for Vulkan on macOS and OpenGL elsewhere; {@code -Drengl.demo.api=OPENGL} chooses.
 * {@code -Drengl.demo.physics=physx} steps the same world with NVIDIA PhysX ({@code rengl-physx}) in place of the
 * built-in solver; nothing else in the demo changes.
 */
public class PhysicsDemo {
    private static final Mesh CUBE = Mesh.cube(1f);
    private static final Mesh BALL = Mesh.sphere(0.3f, 32, 16);
    private static final int MAX_SHOTS = 40;

    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("Physics Demo").setDimensions(1100, 700).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1100, 700);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        Playground playground = new Playground();

        TextOverlay title = new TextOverlay("Physics", 24f, Color.WHITE);
        title.setPosition(24, 18);
        root.addElement(title);

        Viewport3D view = new Viewport3D(760, 660);
        view.setPosition(320, 20);
        view.getStyling().setCornerRadius(12f);
        view.setContent(playground.scene);
        view.setInputListener(new Viewport3D.InputListener() {
            @Override
            public void dragged(double x, double y, double dx, double dy) {
                playground.orbit.dragged(x, y, dx, dy);
            }

            @Override
            public void scrolled(double dx, double dy, double x, double y) {
                playground.orbit.scrolled(dx, dy, x, y);
            }

            @Override
            public void clicked(double x, double y) {
                playground.shoot(playground.scene.camera().screenRay(x, y, view.getWidth(), view.getHeight()));
            }

            @Override
            public void pointerMoved(double x, double y) {
                playground.hover(playground.scene.camera().screenRay(x, y, view.getWidth(), view.getHeight()));
            }
        });
        root.addElement(view);

        TextOverlay hint = new TextOverlay("Click to shoot, drag to orbit, scroll to zoom", 13f, new Color(1f, 1f, 1f, 0.55f));
        hint.setPosition(336, 664);
        root.addElement(hint);

        TextOverlay stats = new TextOverlay("", 14f, Color.WHITE);
        stats.setPosition(24, 66);
        root.addElement(stats);
        playground.onStats = stats::setText;

        TextOverlay under = new TextOverlay("", 14f, new Color(1f, 1f, 1f, 0.8f));
        under.setPosition(24, 92);
        root.addElement(under);
        playground.onHover = under::setText;

        float y = 136;
        y = button(root, "Reset", y, playground::reset);
        y = button(root, "Drop 20 balls", y, playground::dropBalls);
        y = button(root, "Wake everything", y, playground::wakeAll);
        y += 8;
        y = checkBox(root, "Paused", y, false, playground::setPaused);
        y = checkBox(root, "Slippery floor (ice)", y, false, playground::setIce);
        y = checkBox(root, "Bouncy balls", y, true, on -> playground.bouncy = on);
        y = checkBox(root, "Low gravity", y, false, on -> playground.world.setGravity(new Vec3(0, on ? -1.6f : -9.81f, 0)));
        checkBox(root, "Show colliders", y, false, playground.colliders::setVisible);

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    private static float button(Container root, String label, float y, Runnable action) {
        ButtonOverlay button = new ButtonOverlay(label, 264, 32);
        button.setPosition(24, y);
        button.onAction(action);
        root.addElement(button);
        return y + 40;
    }

    private static float checkBox(Container root, String label, float y, boolean checked, java.util.function.Consumer<Boolean> onToggle) {
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
     * The scene, its physics world, and what the panel changes. Everything here runs on the render thread: the
     * overlays' handlers and the viewport's input both do.
     */
    private static final class Playground {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final PhysicsWorld world = newWorld();
        final ColliderView colliders = new ColliderView(world).setVisible(false);
        final List<RigidBody> crates = new ArrayList<>();
        final Deque<RigidBody> shots = new ArrayDeque<>();
        final Random random = new Random(1);
        final Material ballMaterial = new Material(new Color(0.9f, 0.2f, 0.15f, 1f)).setSpecular(0.7f).setShininess(48);
        final Material[] crateMaterials = {
                new Material(new Color(0.78f, 0.55f, 0.32f, 1f)),
                new Material(new Color(0.65f, 0.45f, 0.26f, 1f)),
                new Material(new Color(0.85f, 0.66f, 0.40f, 1f))};
        RigidBody floor;
        boolean bouncy = true;
        boolean paused;
        java.util.function.Consumer<String> onStats = _ -> {
        };
        java.util.function.Consumer<String> onHover = _ -> {
        };
        private String shownStats = "";

        Playground() {
            scene.setBackground(new Color(0.13f, 0.15f, 0.21f, 1f));
            scene.setAmbient(Color.WHITE, 0.15f);
            scene.lights().getFirst().setShadows(Shadows.of(ShadowQuality.HIGH).cascades(2).distance(45f));
            scene.setPhysics(world);
            scene.add(colliders.getNode());
            scene.setUpdateHandler((s, dt) -> updateStats());

            Node3D floorNode = new Node3D("floor", Mesh.plane(40, 40), new Material(
                    TextureData.checker(256, 20, new float[]{0.55f, 0.55f, 0.55f}, new float[]{0.32f, 0.32f, 0.35f})).setSpecular(0.05f));
            scene.add(floorNode);
            floor = new RigidBody(floorNode, BodyType.STATIC).addShape(new PlaneShape());
            world.add(floor);

            Node3D ramp = new Node3D("ramp", CUBE, new Material(new Color(0.35f, 0.5f, 0.7f, 1f)));
            ramp.setScale(new Vec3(7f, 0.3f, 3f));
            ramp.setPosition(-7f, 1.6f, 2f);
            ramp.setRotation(Quat.fromAxisAngle(Vec3.Z, -0.35f));
            scene.add(ramp);
            world.add(new RigidBody(ramp, BodyType.STATIC).addShape(BoxShape.ofSize(1, 1, 1)));

            orbit.setTarget(new Vec3(0, 1.5f, 0)).setDistance(18f).setAngles(0.5f, 0.35f);
            buildCrates();
        }

        private void buildCrates() {
            int rows = 6;
            for (int row = 0; row < rows; row++) {
                for (int i = 0; i < rows - row; i++) {
                    crate(new Vec3(i * 1.02f - (rows - row - 1) * 0.51f, 0.5f + row, -3f));
                }
            }
            for (int i = 0; i < 8; i++) crate(new Vec3(5f, 0.5f + i, 1f));
        }

        private void crate(Vec3 position) {
            Node3D node = new Node3D("crate", CUBE, crateMaterials[crates.size() % crateMaterials.length]);
            node.setPosition(position);
            scene.add(node);
            RigidBody body = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setMass(10f).setMaterial(PhysicsMaterial.WOOD);
            world.add(body);
            crates.add(body);
        }

        private RigidBody ball(Vec3 position, Vec3 velocity) {
            Node3D node = new Node3D("ball", BALL, ballMaterial);
            node.setPosition(position);
            scene.add(node);
            RigidBody body = new RigidBody(node).addShape(new SphereShape(0.3f)).setMass(8f)
                    .setMaterial(bouncy ? PhysicsMaterial.RUBBER : PhysicsMaterial.DEFAULT).setVelocity(velocity);
            world.add(body);
            shots.addLast(body);
            while (shots.size() > MAX_SHOTS) remove(shots.removeFirst());
            return body;
        }

        void shoot(Ray ray) {
            Vec3 direction = ray.direction().normalized();
            ball(ray.origin().add(direction.mul(1f)), direction.mul(28f));
        }

        void hover(Ray ray) {
            RaycastHit hit = world.raycast(Ray.of(ray.origin(), ray.direction()), 200f);
            String text = hit == null ? "Under the mouse: nothing"
                    : "Under the mouse: " + hit.body().getNode().getName() + String.format(" at %.1f m%s", hit.distance(),
                    hit.body().isSleeping() ? " (asleep)" : "");
            onHover.accept(text);
        }

        void dropBalls() {
            for (int i = 0; i < 20; i++) {
                ball(new Vec3(-9.5f + random.nextFloat() * 2f, 6f + i * 0.7f, 1f + random.nextFloat() * 2f), Vec3.ZERO);
            }
        }

        void wakeAll() {
            for (RigidBody body : world.bodies()) body.wakeUp();
        }

        void reset() {
            for (RigidBody body : new ArrayList<>(crates)) remove(body);
            for (RigidBody body : new ArrayList<>(shots)) remove(body);
            crates.clear();
            shots.clear();
            buildCrates();
        }

        void setPaused(boolean paused) {
            this.paused = paused;
            scene.setPhysics(paused ? null : world);
        }

        void setIce(boolean ice) {
            floor.setMaterial(ice ? PhysicsMaterial.ICE : PhysicsMaterial.DEFAULT);
            wakeAll();
        }

        private void remove(RigidBody body) {
            world.remove(body);
            scene.root().remove(body.getNode());
        }

        /**
         * A world on the built-in solver, or on PhysX with {@code -Drengl.demo.physics=physx} when its natives loaded.
         */
        private static PhysicsWorld newWorld() {
            PhysicsWorld world = new PhysicsWorld();
            if ("physx".equalsIgnoreCase(System.getProperty("rengl.demo.physics"))) {
                if (PhysX.isAvailable()) world.setBackend(new PhysXBackend());
                else System.err.println("PhysX is not available, using the built-in solver: " + PhysX.getFailure());
            }
            return world;
        }

        private void updateStats() {
            int awake = 0;
            for (RigidBody body : world.bodies()) if (body.isDynamic() && !body.isSleeping()) awake++;
            String text = world.bodies().size() + " bodies, " + awake + " awake, " + world.getContacts().size() + " contacts"
                    + (world.getBackend() == null ? "" : " (" + world.getBackend().name() + ")");
            if (!text.equals(shownStats)) {
                shownStats = text;
                onStats.accept(text);
            }
        }
    }
}
