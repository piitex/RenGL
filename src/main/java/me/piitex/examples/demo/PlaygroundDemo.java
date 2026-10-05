package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.Input;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BodyType;
import me.piitex.engine.render3d.physics.BoxShape;
import me.piitex.engine.render3d.physics.CharacterController;
import me.piitex.engine.render3d.physics.Contact;
import me.piitex.engine.render3d.physics.ContactListener;
import me.piitex.engine.render3d.physics.DistanceJoint;
import me.piitex.engine.render3d.physics.HingeJoint;
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
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Joints and the character controller: walk a character around a playground.
 * <ul>
 *   <li>The character is a {@link CharacterController}: it climbs the stairs and the gentle ramp but not the steep one,
 *       rides the lift and the moving platform, and pushes the crates, the door and the seesaw with its weight.</li>
 *   <li>The rope bridge is planks joined end to end by {@link HingeJoint}s; it sags under the character. The door turns
 *       on a hinge with limits, the seesaw on one in the middle, and the wrecking ball swings on a
 *       {@link DistanceJoint} rope and knocks the character back.</li>
 *   <li>WASD walks (relative to the camera), Shift runs, Space jumps, C rebuilds the crate stack, R goes back to the
 *       start. Drag to turn the camera and scroll to zoom.</li>
 * </ul>
 * Like the 3D demo it asks for Vulkan on macOS and OpenGL elsewhere; {@code -Drengl.demo.api=OPENGL} chooses.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class PlaygroundDemo {
    private static final Mesh CUBE = Mesh.cube(1f);
    private static final Vec3 START = new Vec3(0f, 0f, 7f);

    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("Playground Demo").setDimensions(1200, 760).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1200, 760);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        Playground playground = new Playground(window.getInput());

        TextOverlay title = new TextOverlay("Playground", 24f, Color.WHITE);
        title.setPosition(24, 18);
        root.addElement(title);
        String[] lines = {"WASD  walk", "Shift  run", "Space  jump", "C  rebuild the crates", "R  back to the start",
                "Drag  turn the camera", "Scroll  zoom"};
        for (int i = 0; i < lines.length; i++) {
            TextOverlay line = new TextOverlay(lines[i], 14f, new Color(1f, 1f, 1f, 0.75f));
            line.setPosition(24, 70 + i * 24);
            root.addElement(line);
        }
        TextOverlay[] stats = new TextOverlay[4];
        for (int i = 0; i < stats.length; i++) {
            stats[i] = new TextOverlay("", 14f, Color.WHITE);
            stats[i].setPosition(24, 260 + i * 22);
            root.addElement(stats[i]);
        }
        playground.onStats = text -> {
            String[] parts = text.split("\n", -1);
            for (int i = 0; i < stats.length; i++) stats[i].setText(i < parts.length ? parts[i] : "");
        };

        Viewport3D view = new Viewport3D(940, 720);
        view.setPosition(240, 20);
        view.getStyling().setCornerRadius(12f);
        view.setContent(playground.scene);
        view.setInputListener(playground.orbit);
        root.addElement(view);

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    /** The scene, its world and the character. Everything here runs on the render thread. */
    private static final class Playground {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final PhysicsWorld world = new PhysicsWorld();
        final Input input;
        final CharacterController player;
        final Node3D model;
        final List<RigidBody> crates = new ArrayList<>();
        RigidBody lift, shuttle, ball;
        Node3D rope;
        Vec3 pivot;
        Material padMaterial;
        boolean onPad;
        java.util.function.Consumer<String> onStats = _ -> {
        };
        private String shownStats = "";

        Playground(Input input) {
            this.input = input;
            scene.setBackground(new Color(0.55f, 0.7f, 0.85f, 1f));
            scene.setAmbient(Color.WHITE, 0.3f);
            scene.lights().getFirst().setShadows(Shadows.of(ShadowQuality.HIGH).cascades(3).distance(40f));

            Node3D floor = new Node3D("floor", Mesh.plane(60, 60), new Material(
                    TextureData.checker(256, 30, new float[]{0.55f, 0.57f, 0.6f}, new float[]{0.5f, 0.52f, 0.55f})).setSpecular(0.05f));
            scene.add(floor);
            world.add(new RigidBody(floor, BodyType.STATIC).addShape(new PlaneShape()));

            Node3D playerNode = new Node3D("player");
            model = new Node3D("model");
            model.add(new Node3D("body", Mesh.sphere(1f, 24, 16), new Material(new Color(0.9f, 0.45f, 0.2f, 1f)).setSpecular(0.3f))
                    .setPosition(0f, 0.9f, 0f).setScale(new Vec3(0.35f, 0.9f, 0.35f)));
            model.add(new Node3D("visor", CUBE, new Material(new Color(0.1f, 0.12f, 0.15f, 1f)).setSpecular(0.8f))
                    .setPosition(0f, 1.45f, -0.27f).setScale(new Vec3(0.4f, 0.14f, 0.12f)));
            playerNode.add(model);
            playerNode.setPosition(START);
            scene.add(playerNode);
            player = new CharacterController(playerNode, 0.35f, 1.8f);
            world.addCharacter(player);

            stairsAndBridge();
            ramps();
            platforms();
            door();
            seesaw();
            wreckingBall();
            pad();
            buildCrates();

            orbit.setDistance(9f).setAngles(0.3f, 0.35f).setDistanceLimits(3f, 30f);
            scene.setUpdateHandler((_, dt) -> update(dt));
        }

        private void update(float dt) {
            // Walk relative to where the camera looks.
            Vec3 look = scene.camera().getTarget().sub(scene.camera().getPosition());
            Vec3 forward = new Vec3(look.x(), 0f, look.z()).normalized();
            Vec3 right = forward.cross(Vec3.UP);
            float ahead = (input.isKeyDown(GLFW_KEY_W) ? 1f : 0f) - (input.isKeyDown(GLFW_KEY_S) ? 1f : 0f);
            float side = (input.isKeyDown(GLFW_KEY_D) ? 1f : 0f) - (input.isKeyDown(GLFW_KEY_A) ? 1f : 0f);
            Vec3 direction = forward.mul(ahead).add(right.mul(side)).normalized();
            boolean running = input.isKeyDown(GLFW_KEY_LEFT_SHIFT) || input.isKeyDown(GLFW_KEY_RIGHT_SHIFT);
            player.setWalkVelocity(direction.mul(running ? 7f : 3.5f));
            if (input.isKeyJustPressed(GLFW_KEY_SPACE)) player.jump(5.5f);
            if (input.isKeyJustPressed(GLFW_KEY_R)) {
                player.setPosition(START);
                player.setVelocity(Vec3.ZERO);
            }
            if (input.isKeyJustPressed(GLFW_KEY_C)) buildCrates();

            world.update(dt);

            // Turn the model to face where it walks; the body itself stays upright.
            if (!direction.equals(Vec3.ZERO)) {
                Quat facing = Quat.fromAxisAngle(Vec3.UP, (float) Math.atan2(-direction.x(), -direction.z()));
                model.setRotation(model.getRotation().slerp(facing, Math.min(1f, dt * 12f)));
            }
            orbit.setTarget(player.getNode().getPosition().add(new Vec3(0f, 1.4f, 0f)));
            // The rope is drawn as a thin box from the beam to the ball.
            Vec3 end = ball.getNode().getPosition(), span = pivot.sub(end);
            rope.setPosition(end.add(span.mul(0.5f)));
            rope.setScale(new Vec3(0.04f, span.length(), 0.04f));
            Vec3 axis = Vec3.UP.cross(span.normalized());
            float angle = (float) Math.acos(Math.clamp(span.normalized().y(), -1f, 1f));
            rope.setRotation(axis.length() < 1e-6f ? Quat.IDENTITY : Quat.fromAxisAngle(axis, angle));
            if (player.getPosition().y() < -20f) player.setPosition(START);
            updateStats();
        }

        // ---- The course ---------------------------------------------------------------------------------------

        /** Stairs up to a tower, a rope bridge to a second tower, and a ramp back down. */
        private void stairsAndBridge() {
            Color stone = new Color(0.62f, 0.6f, 0.56f, 1f);
            box("tower", new Vec3(-6f, 1f, -6f), new Vec3(3f, 2f, 2.4f), stone);
            box("tower", new Vec3(3f, 1f, -6f), new Vec3(3f, 2f, 2.4f), stone);
            for (int i = 1; i <= 10; i++) { // 0.2 m steps, 0.35 m deep, rising toward the tower
                float h = 0.2f * i;
                box("step", new Vec3(-6f, h * 0.5f, -4.8f + (10 - i) * 0.35f + 0.175f), new Vec3(3f, h, 0.35f), stone);
            }

            // Planks along a shallow curve between the towers (x = -4.5 to 1.5), hinged end to end.
            int count = 12;
            float length = 0.51f, thickness = 0.08f;
            float lo = 0f, hi = 1.5f;
            for (int k = 0; k < 60; k++) {
                float a = (lo + hi) * 0.5f, span = 0f;
                for (int i = 0; i < count; i++) span += length * (float) Math.cos(-a + 2 * a * (i + 0.5f) / count);
                if (span > 6f) lo = a;
                else hi = a;
            }
            Material wood = new Material(new Color(0.55f, 0.38f, 0.22f, 1f)).setSpecular(0.1f);
            RigidBody previous = null;
            Vec3 hinge = new Vec3(-4.5f, 2f - thickness * 0.5f, -6f);
            for (int i = 0; i < count; i++) {
                float angle = -lo + 2 * lo * (i + 0.5f) / count;
                Vec3 next = hinge.add(new Vec3((float) Math.cos(angle), (float) Math.sin(angle), 0f).mul(length));
                Node3D node = new Node3D("plank", CUBE, wood);
                node.setPosition(hinge.add(next).mul(0.5f)).setRotation(Quat.fromAxisAngle(Vec3.Z, angle));
                node.setScale(new Vec3(length - 0.02f, thickness, 1.6f));
                scene.add(node);
                RigidBody plank = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setMass(10f).setMaterial(PhysicsMaterial.WOOD);
                world.add(plank);
                world.addJoint(new HingeJoint(plank, previous, hinge, Vec3.Z));
                previous = plank;
                hinge = next;
            }
            world.addJoint(new HingeJoint(previous, null, hinge, Vec3.Z));

            // Down from the second tower: a 20 degree ramp toward the camera.
            float slope = (float) Math.toRadians(20), ramp = 2f / (float) Math.sin(slope);
            Quat tilt = Quat.fromAxisAngle(Vec3.X, slope);
            Vec3 top = new Vec3(3f, 1f, -4.8f + ramp * 0.5f * (float) Math.cos(slope));
            box("ramp", top.sub(tilt.rotate(new Vec3(0f, 0.3f, 0f))), new Vec3(3f, 0.6f, ramp), tilt, stone);
        }

        /** A gentle ramp and a steep one, side by side. */
        private void ramps() {
            Color clay = new Color(0.72f, 0.5f, 0.38f, 1f);
            box("gentle ramp", new Vec3(-10f, 0f, 1f), new Vec3(2f, 1f, 6f), Quat.fromAxisAngle(Vec3.X, (float) Math.toRadians(-15)), clay);
            box("steep ramp", new Vec3(-13f, 0f, 1f), new Vec3(2f, 1f, 6f), Quat.fromAxisAngle(Vec3.X, (float) Math.toRadians(-55)), clay);
        }

        /** A lift up to a high ledge, and a platform that shuttles back and forth. */
        private void platforms() {
            Color metal = new Color(0.45f, 0.5f, 0.58f, 1f);
            box("ledge", new Vec3(11.5f, 1.5f, -6f), new Vec3(3f, 3f, 3f), new Color(0.62f, 0.6f, 0.56f, 1f));
            lift = box("lift", new Vec3(8.75f, 0.1f, -6f), new Vec3(2.4f, 0.2f, 2.4f), Quat.IDENTITY, metal, BodyType.KINEMATIC);
            shuttle = box("shuttle", new Vec3(7f, 0.4f, 3f), new Vec3(2.4f, 0.2f, 2.4f), Quat.IDENTITY, metal, BodyType.KINEMATIC);
            world.setStepHandler((w, dt) -> {
                double t = w.getTime() + dt;
                // Each heads for where it should be at the end of the step, so they never drift.
                float y = 0.1f + 1.4f * (1f - (float) Math.cos(t * 0.6)); // from the floor to level with the ledge and back
                lift.setVelocity(new Vec3(0f, (y - lift.getPosition().y()) / dt, 0f));
                float x = 7f + 3.5f * (float) Math.sin(t * 0.5);
                shuttle.setVelocity(new Vec3((x - shuttle.getPosition().x()) / dt, 0f, 0f));
            });
        }

        /** A wall with a door that turns on a hinge, stopping short of the wall either way. */
        private void door() {
            Color plaster = new Color(0.85f, 0.83f, 0.78f, 1f);
            box("wall", new Vec3(-11.05f, 1.25f, 9f), new Vec3(6f, 2.5f, 0.2f), plaster);
            box("wall", new Vec3(-4f, 1.25f, 9f), new Vec3(6f, 2.5f, 0.2f), plaster);
            box("lintel", new Vec3(-7.5f, 2.35f, 9f), new Vec3(1f, 0.3f, 0.2f), plaster);
            Node3D node = new Node3D("door", CUBE, new Material(new Color(0.45f, 0.28f, 0.16f, 1f)).setSpecular(0.2f));
            node.setPosition(-7.5f, 1.06f, 9f).setScale(new Vec3(0.94f, 2.08f, 0.08f));
            scene.add(node);
            RigidBody door = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setMass(25f).setAngularDamping(2f);
            world.add(door);
            world.addJoint(new HingeJoint(door, null, new Vec3(-7.97f, 1.06f, 9f), Vec3.UP).setLimits(-1.7f, 1.7f));
        }

        /** A plank balanced on a hinge; a crate from {@link #buildCrates()} weighs down one end. */
        private void seesaw() {
            box("pivot", new Vec3(12f, 0.275f, 6f), new Vec3(0.3f, 0.55f, 1.2f), new Color(0.4f, 0.4f, 0.42f, 1f));
            Node3D node = new Node3D("seesaw", CUBE, new Material(new Color(0.3f, 0.55f, 0.35f, 1f)).setSpecular(0.1f));
            node.setPosition(12f, 0.65f, 6f).setScale(new Vec3(5f, 0.1f, 1.2f));
            scene.add(node);
            RigidBody plank = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setMass(15f);
            world.add(plank);
            world.addJoint(new HingeJoint(plank, null, new Vec3(12f, 0.65f, 6f), Vec3.Z).setLimits(-0.22f, 0.22f));
        }

        /** A heavy ball on a rope from a high beam, pulled back to swing through where the crates stand. */
        private void wreckingBall() {
            pivot = new Vec3(4f, 7f, 10f);
            box("beam", pivot.add(new Vec3(0f, 0.2f, 0f)), new Vec3(1f, 0.4f, 1f), new Color(0.3f, 0.3f, 0.32f, 1f));
            float length = 5.5f, pulled = 1.1f;
            Node3D node = new Node3D("ball", Mesh.sphere(0.6f, 24, 16), new Material(new Color(0.15f, 0.15f, 0.17f, 1f)).setSpecular(0.9f));
            node.setPosition(pivot.add(new Vec3(-(float) Math.sin(pulled) * length, -(float) Math.cos(pulled) * length, 0f)));
            scene.add(node);
            ball = new RigidBody(node).addShape(new SphereShape(0.6f)).setMass(300f).setAngularDamping(0.2f).setSleepingAllowed(false);
            world.add(ball);
            world.addJoint(new DistanceJoint(ball, node.getPosition(), null, pivot).setMinLength(0f));
            rope = new Node3D("rope", CUBE, new Material(new Color(0.25f, 0.2f, 0.15f, 1f)));
            scene.add(rope);
            ball.setContactListener(new ContactListener() {
                @Override
                public void contactBegin(Contact contact) {
                    if (contact.getOther(ball) != player.getBody()) return;
                    Vec3 v = ball.getVelocity();
                    player.setVelocity(new Vec3(v.x() * 1.5f, 4f, v.z() * 1.5f)); // knocked flying
                }
            });
        }

        /** A pad that lights up while the character stands on it. */
        private void pad() {
            padMaterial = new Material(new Color(0.2f, 0.25f, 0.3f, 1f));
            Node3D node = new Node3D("pad", CUBE, padMaterial);
            node.setPosition(0f, 0.01f, 1f).setScale(new Vec3(2f, 0.02f, 2f));
            scene.add(node);
            RigidBody zone = new RigidBody(BodyType.STATIC).addShape(new BoxShape(1f, 0.5f, 1f)).setTrigger(true);
            zone.setPosition(0f, 0.5f, 1f);
            world.add(zone);
            zone.setContactListener(new ContactListener() {
                @Override
                public void triggerEnter(RigidBody trigger, RigidBody other) {
                    if (other == player.getBody()) light(true);
                }

                @Override
                public void triggerExit(RigidBody trigger, RigidBody other) {
                    if (other == player.getBody()) light(false);
                }
            });
        }

        private void light(boolean on) {
            onPad = on;
            padMaterial.setColor(on ? new Color(0.3f, 0.9f, 0.5f, 1f) : new Color(0.2f, 0.25f, 0.3f, 1f));
            padMaterial.setEmissive(on ? new Color(0.3f, 0.9f, 0.5f, 1f) : Color.BLACK, on ? 1.5f : 0f);
        }

        /** A stack of crates under the wrecking ball, a few loose ones to push, and one too heavy to. */
        private void buildCrates() {
            for (RigidBody crate : crates) {
                world.remove(crate);
                scene.root().remove(crate.getNode());
            }
            crates.clear();
            Material wood = new Material(new Color(0.7f, 0.52f, 0.3f, 1f)).setSpecular(0.2f);
            for (int row = 0; row < 3; row++) {
                for (int i = 0; i < 3 - row; i++) crate(new Vec3(4f - 0.85f + i * 0.85f + row * 0.425f, 0.4f + row * 0.8f, 10f), 0.8f, 20f, wood);
            }
            for (int i = 0; i < 4; i++) crate(new Vec3(-2f + i * 1.3f, 0.35f, 4f), 0.7f, 15f, wood);
            crate(new Vec3(4f, 0.6f, 5f), 1.2f, 2000f, new Material(new Color(0.35f, 0.35f, 0.38f, 1f)).setSpecular(0.5f));
            crate(new Vec3(13.9f, 1.15f, 6f), 0.8f, 20f, wood); // on the seesaw
        }

        private void crate(Vec3 at, float size, float mass, Material material) {
            Node3D node = new Node3D("crate", CUBE, material);
            node.setPosition(at).setScale(size);
            scene.add(node);
            RigidBody crate = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1)).setMass(mass).setMaterial(PhysicsMaterial.WOOD);
            world.add(crate);
            crates.add(crate);
        }

        private RigidBody box(String name, Vec3 center, Vec3 size, Color color) {
            return box(name, center, size, Quat.IDENTITY, color, BodyType.STATIC);
        }

        private RigidBody box(String name, Vec3 center, Vec3 size, Quat rotation, Color color) {
            return box(name, center, size, rotation, color, BodyType.STATIC);
        }

        private RigidBody box(String name, Vec3 center, Vec3 size, Quat rotation, Color color, BodyType type) {
            Node3D node = new Node3D(name, CUBE, new Material(color).setSpecular(0.1f));
            node.setPosition(center).setRotation(rotation).setScale(size);
            scene.add(node);
            RigidBody body = new RigidBody(node, type).addShape(BoxShape.ofSize(1, 1, 1));
            world.add(body);
            return body;
        }

        private void updateStats() {
            RigidBody ground = player.getGroundBody();
            String standing = ground == null ? "nothing" : ground.getNode() == null ? "?" : ground.getNode().getName();
            Vec3 v = player.getVelocity();
            String text = String.format(Locale.ROOT, "%s\nspeed %.1f m/s\non %s%s",
                    player.isGrounded() ? "on the ground" : "in the air", Math.hypot(v.x(), v.z()), standing, onPad ? "\non the pad" : "");
            if (!text.equals(shownStats)) {
                shownStats = text;
                onStats.accept(text);
            }
        }
    }
}
