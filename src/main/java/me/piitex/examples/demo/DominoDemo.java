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
import me.piitex.engine.render3d.physics.PhysicsMaterial;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.PlaneShape;
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

/**
 * A quick physics demo: a row of dominoes along an S-curve. Push the first one (or click any domino) and the chain
 * falls end to end. Each domino is a dynamic box that sleeps until something hits it.
 * <p>
 * The world isn't given to the scene with {@link Scene3D#setPhysics}: the update handler calls
 * {@link PhysicsWorld#update} itself so it can feed in a quarter of the frame time for slow motion.
 * Like the 3D demo it asks for Vulkan on macOS and OpenGL elsewhere; {@code -Drengl.demo.api=OPENGL} chooses.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class DominoDemo {
    private static final float THICK = 0.12f, HEIGHT = 1.2f, WIDE = 0.6f, GAP = 0.6f;
    private static final Mesh DOMINO = Mesh.cube(1f);

    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("Domino Demo").setDimensions(1100, 700).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1100, 700);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        Dominoes dominoes = new Dominoes();

        TextOverlay title = new TextOverlay("Dominoes", 24f, Color.WHITE);
        title.setPosition(24, 18);
        root.addElement(title);

        Viewport3D view = new Viewport3D(760, 660);
        view.setPosition(320, 20);
        view.getStyling().setCornerRadius(12f);
        view.setContent(dominoes.scene);
        view.setInputListener(new Viewport3D.InputListener() {
            @Override
            public void dragged(double x, double y, double dx, double dy) {
                dominoes.orbit.dragged(x, y, dx, dy);
            }

            @Override
            public void scrolled(double dx, double dy, double x, double y) {
                dominoes.orbit.scrolled(dx, dy, x, y);
            }

            @Override
            public void clicked(double x, double y) {
                dominoes.poke(dominoes.scene.camera().screenRay(x, y, view.getWidth(), view.getHeight()));
            }
        });
        root.addElement(view);

        TextOverlay hint = new TextOverlay("Click a domino to push it, drag to orbit, scroll to zoom", 13f, new Color(1f, 1f, 1f, 0.55f));
        hint.setPosition(336, 664);
        root.addElement(hint);

        TextOverlay stats = new TextOverlay("", 14f, Color.WHITE);
        stats.setPosition(24, 66);
        root.addElement(stats);
        dominoes.onStats = stats::setText;

        ButtonOverlay push = new ButtonOverlay("Push the first domino", 264, 32);
        push.setPosition(24, 110);
        push.onAction(dominoes::pushFirst);
        root.addElement(push);

        ButtonOverlay reset = new ButtonOverlay("Reset", 264, 32);
        reset.setPosition(24, 150);
        reset.onAction(dominoes::reset);
        root.addElement(reset);

        CheckBoxOverlay slow = new CheckBoxOverlay(20, false);
        slow.setPosition(24, 198);
        slow.onToggle(on -> dominoes.speed = on ? 0.25f : 1f);
        root.addElement(slow);
        TextOverlay slowText = new TextOverlay("Slow motion", 14f, Color.WHITE);
        slowText.setPosition(54, 199);
        root.addElement(slowText);

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    /** The scene and its world. Everything here runs on the render thread. */
    private static final class Dominoes {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final PhysicsWorld world = new PhysicsWorld();
        final List<RigidBody> chain = new ArrayList<>();
        float speed = 1f;
        java.util.function.Consumer<String> onStats = _ -> {
        };
        private String shownStats = "";

        Dominoes() {
            scene.setBackground(new Color(0.13f, 0.15f, 0.21f, 1f));
            scene.setAmbient(Color.WHITE, 0.2f);
            scene.lights().getFirst().setShadows(Shadows.of(ShadowQuality.HIGH).cascades(2).distance(30f));
            scene.setUpdateHandler((_, dt) -> {
                world.update(dt * speed);
                updateStats();
            });

            Node3D floor = new Node3D("floor", Mesh.plane(40, 40), new Material(
                    TextureData.checker(256, 20, new float[]{0.55f, 0.55f, 0.55f}, new float[]{0.32f, 0.32f, 0.35f})).setSpecular(0.05f));
            scene.add(floor);
            world.add(new RigidBody(floor, BodyType.STATIC).addShape(new PlaneShape()));

            orbit.setTarget(new Vec3(0, 0.5f, 0)).setDistance(17f).setAngles(0.3f, 0.6f);
            build();
        }

        /** Stands a domino every {@link #GAP} metres along z = 3 sin(0.4 x), each turned to face along the curve. */
        private void build() {
            float x = -8f, travelled = GAP;
            while (x < 8f) {
                float next = x + 0.01f;
                float z = curve(x), nextZ = curve(next);
                travelled += (float) Math.hypot(next - x, nextZ - z);
                if (travelled >= GAP) {
                    travelled = 0f;
                    float yaw = (float) Math.atan2(-(nextZ - z), next - x);
                    domino(new Vec3(x, HEIGHT / 2, z), Quat.fromAxisAngle(Vec3.UP, yaw));
                }
                x = next;
            }
            int count = chain.size();
            for (int i = 0; i < count; i++) {
                chain.get(i).getNode().setMaterial(new Material(hue(i / (float) count)).setSpecular(0.5f).setShininess(32));
            }
        }

        private static float curve(float x) {
            return 3f * (float) Math.sin(x * 0.4f);
        }

        private void domino(Vec3 position, Quat rotation) {
            Node3D node = new Node3D("domino", DOMINO, new Material(Color.WHITE));
            node.setScale(new Vec3(THICK, HEIGHT, WIDE));
            node.setPosition(position);
            node.setRotation(rotation);
            scene.add(node);
            // The node's scale stretches the unit box the same way it stretches the cube mesh.
            RigidBody body = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setMass(0.5f).setMaterial(PhysicsMaterial.WOOD);
            world.add(body);
            chain.add(body);
        }

        void pushFirst() {
            RigidBody first = chain.getFirst();
            push(first, first.getRotation().rotate(Vec3.X));
        }

        void poke(Ray ray) {
            RaycastHit hit = world.raycast(Ray.of(ray.origin(), ray.direction()), 200f);
            if (hit == null || !hit.body().isDynamic()) return;
            // Push along the domino's thin side, whichever way the click faces.
            Vec3 facing = hit.body().getRotation().rotate(Vec3.X);
            push(hit.body(), ray.direction().dot(facing) < 0 ? facing.mul(-1f) : facing);
        }

        /** A small shove near the top: enough to tip it, not to throw it. */
        private void push(RigidBody domino, Vec3 direction) {
            domino.applyImpulseAtPoint(direction.mul(0.4f), domino.getPosition().add(new Vec3(0, HEIGHT * 0.45f, 0)));
        }

        void reset() {
            for (RigidBody body : chain) {
                world.remove(body);
                scene.root().remove(body.getNode());
            }
            chain.clear();
            build();
        }

        private void updateStats() {
            int fallen = 0;
            for (RigidBody body : chain) if (body.getRotation().rotate(Vec3.UP).y() < 0.9f) fallen++;
            String text = fallen + " of " + chain.size() + " down";
            if (!text.equals(shownStats)) {
                shownStats = text;
                onStats.accept(text);
            }
        }

        private static Color hue(float h) {
            float r = channel(h, 0f), g = channel(h, 1f / 3), b = channel(h, 2f / 3);
            return new Color(0.2f + 0.7f * r, 0.2f + 0.7f * g, 0.2f + 0.7f * b, 1f);
        }

        private static float channel(float h, float offset) {
            return Math.clamp(Math.abs(((h + offset) % 1f) * 6f - 3f) - 1f, 0f, 1f);
        }
    }
}
