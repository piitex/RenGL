package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BodyType;
import me.piitex.engine.render3d.physics.BoxShape;
import me.piitex.engine.render3d.physics.ColliderView;
import me.piitex.engine.render3d.physics.ConvexDecomposition;
import me.piitex.engine.render3d.physics.ConvexHullShape;
import me.piitex.engine.render3d.physics.MeshShape;
import me.piitex.engine.render3d.physics.PhysicsMaterial;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.PlaneShape;
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
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Two things a body made of one box or ball could not do before: be fast, and have dents.
 * <ul>
 *   <li><b>Fast bodies.</b> A wall 2 cm thick stands in the middle of the floor. "Fire" sends a ball, a thin sheet, a plank or a
 *       cube at it, with the speed from the slider; "Volley" sends thirty at random heights, angles and spins. At 300 m/s a
 *       body moves five metres between two steps, which is 250 times the wall, so a body that is only moved and then tested for
 *       contact is on the far side by the time anything is checked. With {@code Continuous collision} on (the default, and what
 *       {@link RigidBody#setContinuous} controls) the body is walked along its path first and stopped at the surface, and a
 *       thin sheet that hits the wall on a corner lies flat against it instead of spinning through. Turn the check box off and
 *       fire again to see them go through. The count under the buttons is the bodies found on the far side of the wall.</li>
 *   <li><b>Dents.</b> A mesh with a dent in it cannot be a moving body as it is: a single hull fills the dent in, and a triangle mesh
 *       has no inside. "Drop L-shaped bodies" lets six L-shaped bodies fall on a staircase. Each is {@link ConvexDecomposition}
 *       of the model into a few convex hulls, so the L keeps its corner, hooks over a step and lies on its side. Show the colliders
 *       to see the hulls.</li>
 * </ul>
 * Drag to orbit, scroll to zoom. Like the other 3D demos it asks for Vulkan on macOS and OpenGL elsewhere;
 * {@code -Drengl.demo.api=OPENGL} chooses.
 */
public class FastBodiesDemo {
    private static final String[] KINDS = {"Thin sheet (turning)", "Ball", "Plank", "Cube"};

    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("Fast bodies and dents").setDimensions(1100, 700).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1100, 700);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        Range range = new Range();

        TextOverlay title = new TextOverlay("Fast bodies and dents", 24f, Color.WHITE);
        title.setPosition(24, 18);
        root.addElement(title);

        Viewport3D view = new Viewport3D(760, 660);
        view.setPosition(320, 20);
        view.getStyling().setCornerRadius(12f);
        view.setContent(range.scene);
        view.setInputListener(range.orbit);
        root.addElement(view);

        TextOverlay hint = new TextOverlay("Drag to orbit, scroll to zoom. The thin line is the wall.", 13f, new Color(1f, 1f, 1f, 0.55f));
        hint.setPosition(336, 664);
        root.addElement(hint);

        TextOverlay speedLabel = new TextOverlay("", 14f, Color.WHITE);
        speedLabel.setPosition(24, 66);
        root.addElement(speedLabel);
        SliderOverlay speed = new SliderOverlay(264, 20, 1000, 300);
        speed.setPosition(24, 90);
        speed.onValueChange(value -> {
            range.speed = value.floatValue();
            speedLabel.setText(String.format("Speed: %.0f m/s, %.1f m a step", range.speed, range.speed * range.world.getFixedStep()));
        });
        root.addElement(speed);
        speedLabel.setText("Speed: 300 m/s, 5.0 m a step");

        DropdownOverlay<String> kind = new DropdownOverlay<>(264, 32);
        for (String name : KINDS) kind.addItem(name);
        kind.setSelectedIndex(0);
        kind.setPosition(24, 128);
        kind.onSelect(name -> range.kind = indexOf(name));
        root.addElement(kind);

        float y = 176;
        y = button(root, "Fire", y, range::fire);
        y = button(root, "Volley of 30", y, range::volley);
        y = button(root, "Drop L-shaped bodies", y, range::dropLs);
        y = button(root, "Reset", y, range::reset);
        y += 6;
        y = checkBox(root, "Continuous collision", y, true, range::setContinuous);
        checkBox(root, "Show colliders", y, false, range.colliders::setVisible);

        TextOverlay[] count = new TextOverlay[4];
        for (int i = 0; i < count.length; i++) {
            count[i] = new TextOverlay("", 14f, Color.WHITE);
            count[i].setPosition(24, 470 + i * 24);
            root.addElement(count[i]);
        }
        range.onCount = text -> {
            String[] parts = text.split(" \\| ");
            for (int i = 0; i < count.length && i < parts.length; i++) count[i].setText(parts[i]);
        };

        new Engine().start(window);
    }

    private static int indexOf(String name) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(name)) return i;
        return 0;
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
     * The floor, the wall, the staircase, and whatever has been fired or dropped.
     */
    private static final class Range {
        private static final float WALL_HALF_WIDTH = 20f;

        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final PhysicsWorld world = new PhysicsWorld();
        final ColliderView colliders = new ColliderView(world).setVisible(false);
        final List<RigidBody> fired = new ArrayList<>();
        final List<RigidBody> dropped = new ArrayList<>();
        final Random random = new Random(4);
        final Mesh cube = Mesh.cube(1f);
        final Mesh ball = Mesh.sphere(0.15f, 24, 12);
        final Material[] materials = {
                new Material(new Color(0.9f, 0.3f, 0.25f, 1f)).setSpecular(0.5f),
                new Material(new Color(0.95f, 0.8f, 0.3f, 1f)).setSpecular(0.5f),
                new Material(new Color(0.4f, 0.8f, 0.5f, 1f)).setSpecular(0.5f)};
        final Material lMaterial = new Material(new Color(0.55f, 0.6f, 0.95f, 1f)).setSpecular(0.4f);
        Consumer<String> onCount = _ -> {
        };
        float speed = 300f;
        int kind;
        boolean continuous = true;
        int shots;
        private String shown = "";

        Range() {
            scene.setBackground(new Color(0.13f, 0.15f, 0.21f, 1f));
            scene.setAmbient(Color.WHITE, 0.2f);
            scene.lights().getFirst().setShadows(Shadows.of(ShadowQuality.HIGH).cascades(2).distance(60f));
            scene.setPhysics(world);
            scene.add(colliders.getNode());
            scene.setUpdateHandler((s, dt) -> updateCount());

            Node3D floor = new Node3D("floor", Mesh.plane(80, 80), new Material(
                    TextureData.checker(256, 40, new float[]{0.5f, 0.5f, 0.52f}, new float[]{0.32f, 0.32f, 0.35f})).setSpecular(0.05f));
            scene.add(floor);
            world.add(new RigidBody(floor, BodyType.STATIC).addShape(new PlaneShape()));

            // 2 cm thick, 40 m wide and 12 m high, in the plane x = 0
            Node3D wall = new Node3D("wall", cube, new Material(new Color(0.3f, 0.85f, 0.95f, 1f)).setSpecular(0.3f));
            wall.setScale(new Vec3(0.02f, 12f, WALL_HALF_WIDTH * 2f));
            wall.setPosition(0f, 6f, 0f);
            scene.add(wall);
            world.add(new RigidBody(wall, BodyType.STATIC).addShape(BoxShape.ofSize(1, 1, 1)));

            buildStairs();
            orbit.setTarget(new Vec3(-6f, 2f, 4f)).setDistance(58f).setAngles(1.0f, 0.45f).setDistanceLimits(6f, 100f);
        }

        /** Eight steps climbing towards +x at the near side, one mesh for the whole staircase. */
        private void buildStairs() {
            Node3D stairs = new Node3D("stairs");
            Material stone = new Material(new Color(0.55f, 0.52f, 0.5f, 1f));
            for (int i = 0; i < 8; i++) {
                float height = (i + 1) * 0.5f;
                Node3D step = new Node3D("step", cube, stone);
                step.setScale(new Vec3(1.6f, height, 6f));
                step.setPosition(i * 1.6f, height / 2f, 0f);
                stairs.add(step);
            }
            stairs.setPosition(-22f, 0f, 14f);
            scene.add(stairs);
            world.add(new RigidBody(stairs, BodyType.STATIC).addShape(MeshShape.of(stairs)));
        }

        // ---- fast bodies ------------------------------------------------------------------------------------------

        void fire() {
            float y = 1f + random.nextFloat() * 8f, z = (random.nextFloat() - 0.5f) * 14f;
            shoot(kind, new Vec3(-14f, y, z), speed, 0.2f + random.nextFloat() * 1.2f);
        }

        void volley() {
            for (int i = 0; i < 30; i++) {
                int k = random.nextInt(KINDS.length);
                float s = Math.max(20f, speed * (0.5f + random.nextFloat()));
                shoot(k, new Vec3(-14f - random.nextFloat() * 6f, 0.8f + random.nextFloat() * 9f, (random.nextFloat() - 0.5f) * 30f), s,
                        random.nextFloat() * 1.5f);
            }
        }

        private void shoot(int what, Vec3 from, float speed, float turn) {
            Node3D node;
            RigidBody body;
            Material material = materials[fired.size() % materials.length];
            switch (what) {
                case 1 -> {
                    node = new Node3D("ball", ball, material);
                    body = new RigidBody(node).addShape(new SphereShape(0.15f)).setMass(2f);
                }
                case 2 -> {
                    node = new Node3D("plank", cube, material);
                    node.setScale(new Vec3(0.05f, 0.05f, 1.4f));
                    body = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setMass(2f);
                }
                case 3 -> {
                    node = new Node3D("cube", cube, material);
                    node.setScale(0.3f);
                    body = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setMass(2f);
                }
                default -> {
                    node = new Node3D("sheet", cube, material);
                    node.setScale(new Vec3(0.02f, 0.8f, 0.8f));
                    body = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setMass(1f);
                }
            }
            node.setPosition(from);
            scene.add(node);
            body.setMaterial(PhysicsMaterial.DEFAULT).setContinuous(continuous);
            body.setRotation(Quat.fromAxisAngle(Vec3.UP, turn));
            body.setVelocity(new Vec3(speed, (random.nextFloat() - 0.5f) * speed * 0.02f, (random.nextFloat() - 0.5f) * speed * 0.05f));
            body.setAngularVelocity(new Vec3(random.nextFloat() * 6f - 3f, random.nextFloat() * 6f - 3f, random.nextFloat() * 6f - 3f));
            world.add(body);
            fired.add(body);
            shots++;
        }

        void setContinuous(boolean on) {
            continuous = on;
            for (RigidBody body : fired) body.setContinuous(on);
        }

        // ---- bodies with dents ------------------------------------------------------------------------------------

        void dropLs() {
            for (int i = 0; i < 6; i++) {
                Node3D node = new Node3D("L");
                Node3D leg = new Node3D("leg", cube, lMaterial);
                leg.setScale(new Vec3(0.4f, 1.6f, 0.4f));
                leg.setPosition(0f, 0.8f, 0f);
                Node3D foot = new Node3D("foot", cube, lMaterial);
                foot.setScale(new Vec3(1.0f, 0.4f, 0.4f));
                foot.setPosition(0.3f, 0.2f, 0f);
                node.add(leg);
                node.add(foot);
                node.setPosition(-18f + random.nextFloat() * 3f, 9f + i * 2.2f, 14f + (random.nextFloat() - 0.5f) * 3f);
                scene.add(node);

                RigidBody body = new RigidBody(node).setMass(6f);
                List<ConvexHullShape> pieces = ConvexDecomposition.of(node, 4);
                for (ConvexHullShape piece : pieces) body.addShape(piece);
                body.setRotation(Quat.fromAxisAngle(new Vec3(random.nextFloat(), random.nextFloat(), random.nextFloat()).normalized(),
                        random.nextFloat() * 6f));
                world.add(body);
                dropped.add(body);
            }
        }

        void reset() {
            for (RigidBody body : new ArrayList<>(fired)) remove(body);
            for (RigidBody body : new ArrayList<>(dropped)) remove(body);
            fired.clear();
            dropped.clear();
            shots = 0;
        }

        private void remove(RigidBody body) {
            world.remove(body);
            scene.root().remove(body.getNode());
        }

        /** Bodies on the far side of the wall's face: x past it, and not round the end of it. */
        private void updateCount() {
            int through = 0;
            for (RigidBody body : fired) {
                Vec3 p = body.getPosition();
                if (p.x() > 0.05f && Math.abs(p.z()) < WALL_HALF_WIDTH - 1f) through++;
            }
            String text = shots + " fired | " + (shots - through) + " on this side of the wall | " + through
                    + " through it | " + dropped.size() + " L-shaped bodies";
            if (!text.equals(shown)) {
                shown = text;
                onCount.accept(text);
            }
        }
    }
}
