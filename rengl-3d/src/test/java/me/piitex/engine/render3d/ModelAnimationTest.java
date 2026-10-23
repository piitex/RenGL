package me.piitex.engine.render3d;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.*;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Model loading (PBR materials, skins, animations), animation playback and the things built on them. No GPU needed.
 */
class ModelAnimationTest {
    private static Model model;

    @BeforeAll
    static void load() throws Exception {
        model = Model.load(Path.of("src/test/resources/models/bar.gltf"));
    }

    private static Node3D copyOf(Model m) {
        return m.root().copy();
    }

    @Test
    void gltfMaterialIsPbrWithItsMaps() {
        Material m = model.root().find("mesh").getMaterial();
        assertTrue(m.isPbr());
        assertEquals(0.25f, m.getMetallic(), 1e-4f);
        assertEquals(0.75f, m.getRoughness(), 1e-4f);
        assertNotNull(m.getTexture());
        assertNotNull(m.getNormalTexture());
        assertNotNull(m.getMetallicRoughnessTexture());
        assertNotNull(m.getEmissiveTexture());
        assertNull(m.getOcclusionTexture());
        assertEquals(0.1f, m.getEmissiveRed(), 1e-3f);
        assertEquals(0.3f, m.getEmissiveBlue(), 1e-3f);
    }

    @Test
    void bonesBecomeASkin() {
        Node3D mesh = model.root().find("mesh");
        assertNotNull(mesh);
        assertTrue(mesh.getMesh().isSkinned());
        Skin skin = mesh.getSkin();
        assertNotNull(skin);
        assertEquals(2, skin.joints().size());
        assertSame(model.root().find("joint0"), skin.joints().get(0));
        assertSame(model.root().find("joint1"), skin.joints().get(1));
        float[] weights = mesh.getMesh().skinWeights();
        for (int v = 0; v < mesh.getMesh().vertexCount(); v++) {
            float sum = weights[v * 4] + weights[v * 4 + 1] + weights[v * 4 + 2] + weights[v * 4 + 3];
            assertEquals(1f, sum, 1e-4f, "weights of vertex " + v);
        }
    }

    @Test
    void animationsLoadAndPlay() {
        assertEquals(1, model.animations().size());
        AnimationClip clip = model.animations().get(0);
        assertEquals("Swing", clip.name());
        assertEquals(2f, clip.duration(), 1e-3f);

        AnimationPlayer player = model.player();
        Node3D joint = model.root().find("joint1");
        player.play("Swing").setLoop(false);
        player.update(1f);
        Quat q = joint.getRotation();
        assertEquals(Math.sin(Math.toRadians(30)), q.z(), 1e-3, "60 degrees about Z at t=1");
        player.update(5f);
        assertFalse(player.isPlaying(), "a clip that is not looped stops at its end");
        assertEquals(0f, joint.getRotation().z(), 1e-3f);
    }

    @Test
    void skinnedMeshIsPickedInItsCurrentPose() {
        Scene3D scene = new Scene3D();
        Model m;
        try {
            m = Model.load(Path.of("src/test/resources/models/bar.gltf"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        scene.add(m.root());
        Ray straight = Ray.of(new Vec3(0, 1.9f, 5), new Vec3(0, 0, -1));
        Ray bent = Ray.of(new Vec3(-0.7f, 1.4f, 5), new Vec3(0, 0, -1));
        assertNotNull(scene.pick(straight), "the top of the bar in its bind pose");
        assertNull(scene.pick(bent));

        AnimationPlayer player = m.player();
        player.play("Swing").setLoop(false);
        player.update(1f);
        assertNull(scene.pick(straight), "the top has swung away");
        Hit hit = scene.pick(bent);
        assertNotNull(hit, "the swung top is now there");
        assertEquals(m.root().find("mesh"), hit.node());
    }

    @Test
    void copiedModelFollowsItsOwnSkeleton() {
        Node3D copy = copyOf(model);
        Node3D mesh = copy.find("mesh");
        Skin skin = mesh.getSkin();
        assertNotSame(model.root().find("mesh").getSkin(), skin);
        assertSame(copy.find("joint1"), skin.joints().get(1));
        assertNotSame(model.root().find("joint1"), skin.joints().get(1));
    }

    @Test
    void crossFadeBlendsTwoPoses() {
        Node3D node = new Node3D("n");
        AnimationClip left = new AnimationClip("left", 1f, List.of(new AnimationClip.Channel(node, AnimationClip.Path.TRANSLATION,
                new float[]{0f}, new float[]{-2, 0, 0}, AnimationClip.Interpolation.LINEAR)));
        AnimationClip right = new AnimationClip("right", 1f, List.of(new AnimationClip.Channel(node, AnimationClip.Path.TRANSLATION,
                new float[]{0f}, new float[]{2, 0, 0}, AnimationClip.Interpolation.LINEAR)));
        AnimationPlayer player = new AnimationPlayer(List.of(left, right));
        player.play("left");
        player.update(0.1f);
        assertEquals(-2f, node.getPosition().x(), 1e-4f);
        player.play("right", 1f);
        player.update(0.5f);
        assertEquals(0f, node.getPosition().x(), 0.05f, "halfway through a one second fade");
        player.update(1f);
        assertEquals(2f, node.getPosition().x(), 1e-4f);
    }

    @Test
    void linearAndStepKeysInterpolate() {
        Node3D node = new Node3D("n");
        float[] times = {0f, 1f, 2f};
        float[] values = {0, 0, 0, 10, 0, 0, 10, 4, 0};
        AnimationPlayer linear = new AnimationPlayer(List.of(new AnimationClip("l", 2f, List.of(new AnimationClip.Channel(node,
                AnimationClip.Path.TRANSLATION, times, values, AnimationClip.Interpolation.LINEAR)))));
        linear.play("l").setTime(0.5f);
        assertEquals(5f, node.getPosition().x(), 1e-4f);
        linear.setTime(1.5f);
        assertEquals(2f, node.getPosition().y(), 1e-4f);
        AnimationPlayer step = new AnimationPlayer(List.of(new AnimationClip("s", 2f, List.of(new AnimationClip.Channel(node,
                AnimationClip.Path.TRANSLATION, times, values, AnimationClip.Interpolation.STEP)))));
        step.play("s").setTime(0.99f);
        assertEquals(0f, node.getPosition().x(), 1e-4f);
    }

    @Test
    void instancesArePickedIndividually() {
        Scene3D scene = new Scene3D();
        Node3D forest = new Node3D("forest", Mesh.cube(1f), new Material(Color.GREEN));
        Instances instances = new Instances();
        for (int i = 0; i < 5; i++) instances.add(new Vec3(i * 3f, 0, -5));
        forest.setInstances(instances);
        scene.add(forest);
        for (int i = 0; i < 5; i++) {
            Hit hit = scene.pick(Ray.of(new Vec3(i * 3f, 0, 0), new Vec3(0, 0, -1)));
            assertNotNull(hit, "instance " + i);
            assertEquals(i, hit.instance());
            assertEquals(4.5f, hit.distance(), 1e-3f);
        }
        assertNull(scene.pick(Ray.of(new Vec3(1.5f, 0, 0), new Vec3(0, 0, -1))), "between instances");
        instances.set(2, me.piitex.engine.render3d.math.Mat4.translation(new Vec3(6f, 0, -8)));
        Hit moved = scene.pick(Ray.of(new Vec3(6f, 0, 0), new Vec3(0, 0, -1)));
        assertEquals(7.5f, moved.distance(), 1e-3f, "an instance that was changed is picked where it is now");
    }

    @Test
    void hierarchyPickingMatchesTestingEveryTriangle() {
        Mesh sphere = Mesh.sphere(1f, 96, 48);
        Node3D node = new Node3D("s", sphere, new Material(Color.RED));
        node.setPosition(0.3f, -0.2f, -4f);
        node.setScale(new Vec3(1.5f, 0.8f, 1.1f));
        Scene3D scene = new Scene3D();
        scene.add(node);
        Random random = new Random(7);
        int hits = 0;
        for (int i = 0; i < 300; i++) {
            Vec3 origin = new Vec3(random.nextFloat() * 6 - 3, random.nextFloat() * 4 - 2, 2f);
            Vec3 target = new Vec3(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, -4f);
            Ray ray = Ray.of(origin, target.sub(origin));
            Hit hit = scene.pick(ray);
            float expected = bruteForce(node, ray);
            if (expected < 0) {
                assertNull(hit, "ray " + i);
            } else {
                hits++;
                assertNotNull(hit, "ray " + i);
                assertEquals(expected, hit.distance(), 1e-3f, "ray " + i);
            }
        }
        assertTrue(hits > 50, "the test rays should hit often enough to mean something, hit " + hits);
    }

    @Test
    void cubeSkyboxNeedsSquareMatchingFaces() {
        TextureData a = TextureData.solid(1, 0, 0, 1);
        TextureData wide = new TextureData(2, 1, new byte[8]);
        assertNotNull(Skybox.cube(a, a, a, a, a, a));
        assertThrows(IllegalArgumentException.class, () -> Skybox.cube(a, a, a, a, a, wide));
    }

    /**
     * The nearest front-facing triangle distance by testing all of them, in doubles; -1 for a miss.
     */
    private static float bruteForce(Node3D node, Ray ray) {
        Mesh mesh = node.getMesh();
        var inverse = node.worldMatrix().inverse();
        Vec3 o = inverse.transformPoint(ray.origin());
        Vec3 d = inverse.transformDirection(ray.direction());
        float[] v = mesh.vertices();
        int[] idx = mesh.indices();
        double best = Double.MAX_VALUE;
        for (int t = 0; t < idx.length; t += 3) {
            int a = idx[t] * 8, b = idx[t + 1] * 8, c = idx[t + 2] * 8;
            double e1x = v[b] - v[a], e1y = v[b + 1] - v[a + 1], e1z = v[b + 2] - v[a + 2];
            double e2x = v[c] - v[a], e2y = v[c + 1] - v[a + 1], e2z = v[c + 2] - v[a + 2];
            double px = d.y() * e2z - d.z() * e2y, py = d.z() * e2x - d.x() * e2z, pz = d.x() * e2y - d.y() * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (det < 1e-12) continue;
            double tx = o.x() - v[a], ty = o.y() - v[a + 1], tz = o.z() - v[a + 2];
            double u = (tx * px + ty * py + tz * pz) / det;
            if (u < 0 || u > 1) continue;
            double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
            double w = (d.x() * qx + d.y() * qy + d.z() * qz) / det;
            if (w < 0 || u + w > 1) continue;
            double distance = (e2x * qx + e2y * qy + e2z * qz) / det;
            if (distance >= 0 && distance < best) best = distance;
        }
        return best == Double.MAX_VALUE ? -1f : (float) (best * ray.direction().length());
    }
}
