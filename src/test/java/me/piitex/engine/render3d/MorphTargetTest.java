package me.piitex.engine.render3d;

import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blend shapes: loading them from a glTF, mixing them, animating their weights, picking and copying. No GPU needed.
 * The test model is a quad on the XZ plane with a target that lifts it by 1 and another that moves it right by 1.
 */
class MorphTargetTest {
    private static Model load() throws Exception {
        return Model.load(Path.of("src/test/resources/models/morph.gltf"));
    }

    @Test
    void targetsLoadAsOffsetsFromTheBase() throws Exception {
        Node3D face = load().root().find("face");
        Mesh mesh = face.getMesh();
        assertTrue(mesh.isMorphed());
        assertEquals(List.of("up", "right"), mesh.morphTargets().stream().map(Mesh.MorphTarget::name).toList());
        float[] up = mesh.morphTargets().get(0).positions();
        float[] right = mesh.morphTargets().get(1).positions();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            assertArrayEquals(new float[]{0, 1, 0}, new float[]{up[v * 3], up[v * 3 + 1], up[v * 3 + 2]}, 1e-5f, "up, vertex " + v);
            assertArrayEquals(new float[]{1, 0, 0}, new float[]{right[v * 3], right[v * 3 + 1], right[v * 3 + 2]}, 1e-5f, "right, vertex " + v);
        }
        assertArrayEquals(new float[]{0, 0}, face.getMorphWeights());
    }

    @Test
    void morphMixesTheShapes() throws Exception {
        Mesh mesh = load().root().find("face").getMesh();
        float[] vertices = mesh.vertices();
        float[] mixed = mesh.morph(new float[]{0.5f, 2f});
        for (int v = 0; v < mesh.vertexCount(); v++) {
            int o = v * Mesh.FLOATS_PER_VERTEX;
            assertEquals(vertices[o] + 2f, mixed[o], 1e-5f);
            assertEquals(vertices[o + 1] + 0.5f, mixed[o + 1], 1e-5f);
            assertEquals(vertices[o + 2], mixed[o + 2], 1e-5f);
            assertEquals(vertices[o + 6], mixed[o + 6], 0f, "texture coordinates are untouched");
        }
        assertEquals(vertices[1], mesh.vertices()[1], 0f, "the mesh itself is not changed");
        assertEquals(0.5f, mesh.morph(new float[]{0.5f})[1] - vertices[1], 1e-5f, "a missing weight counts as 0");
    }

    @Test
    void boundsHoldTheShapesAtFullWeight() throws Exception {
        Mesh mesh = load().root().find("face").getMesh();
        // The quad is x from -1 to 1 and y 0; the shapes can add up to 1 up and 1 right.
        assertTrue(mesh.bounds().max().y() >= 1f - 1e-5f, "up reaches y = 1");
        assertTrue(mesh.bounds().max().x() >= 2f - 1e-5f, "right reaches x = 2");
        assertTrue(mesh.bounds().min().x() <= -1f + 1e-5f);
    }

    @Test
    void theClipAnimatesTheWeights() throws Exception {
        Model model = load();
        Node3D face = model.root().find("face");
        AnimationClip clip = model.animations().get(0);
        assertEquals("smile", clip.name());
        assertEquals(2f, clip.duration(), 1e-4f);
        assertEquals(1, clip.channels().size());
        assertEquals(AnimationClip.Path.WEIGHTS, clip.channels().get(0).path());
        assertSame(face, clip.channels().get(0).target());

        AnimationPlayer player = model.player();
        player.play("smile");
        player.setTime(0f);
        assertArrayEquals(new float[]{0, 0}, face.getMorphWeights(), 1e-5f);
        player.setTime(1f);
        assertArrayEquals(new float[]{1, 0}, face.getMorphWeights(), 1e-5f);
        player.setTime(1.5f);
        assertArrayEquals(new float[]{0.5f, 0.5f}, face.getMorphWeights(), 1e-5f);
        player.setTime(0.5f);
        assertArrayEquals(new float[]{0.5f, 0f}, face.getMorphWeights(), 1e-5f);
    }

    @Test
    void aFadeBlendsTheWeights() throws Exception {
        Model model = load();
        Node3D face = model.root().find("face");
        AnimationClip hold = new AnimationClip("hold", 1f, List.of(new AnimationClip.Channel(face, AnimationClip.Path.WEIGHTS,
                new float[]{0, 1}, new float[]{0, 1, 0, 1}, AnimationClip.Interpolation.LINEAR)));
        AnimationPlayer player = model.player();
        player.play("smile");
        player.setTime(1f);
        assertArrayEquals(new float[]{1, 0}, face.getMorphWeights(), 1e-5f);
        player.play(hold, 1f);
        player.update(0.5f); // the old clip is at 1.5 s: {0.5, 0.5}; the new one holds {0, 1}; halfway between them
        assertArrayEquals(new float[]{0.25f, 0.75f}, face.getMorphWeights(), 1e-5f);
    }

    @Test
    void pickingFollowsTheShape() throws Exception {
        Scene3D scene = new Scene3D();
        Node3D face = load().root();
        scene.add(face);
        Node3D quad = face.find("face");
        Ray down = new Ray(new Vec3(0, 5, -1), new Vec3(0, -1, 0));
        Ray right = new Ray(new Vec3(1.5f, 5, -1), new Vec3(0, -1, 0));

        Hit flat = scene.pick(down);
        assertNotNull(flat);
        assertEquals(5f, flat.distance(), 1e-4f);
        assertNull(scene.pick(right), "the quad does not reach x = 1.5 yet");

        quad.setMorphWeights(1f, 0f);
        assertEquals(4f, scene.pick(down).distance(), 1e-4f, "lifted by one");
        quad.setMorphWeights(0f, 1f);
        assertNotNull(scene.pick(right), "moved right by one, it now covers x = 1.5");
        assertEquals(5f, scene.pick(right).distance(), 1e-4f);
    }

    @Test
    void copiesHaveTheirOwnWeights() throws Exception {
        Node3D original = load().root();
        Node3D copy = original.copy();
        copy.find("face").setMorphWeights(1f, 1f);
        assertArrayEquals(new float[]{0, 0}, original.find("face").getMorphWeights());
        assertArrayEquals(new float[]{1, 1}, copy.find("face").getMorphWeights());
    }

    @Test
    void weightsCanBeSetOneAtATimeAndSharedBetweenParts() {
        Mesh mesh = Mesh.plane(2, 2);
        float[] shift = new float[4 * 3];
        for (int v = 0; v < 4; v++) shift[v * 3 + 1] = 1f;
        mesh.setMorphTargets(List.of(new Mesh.MorphTarget("lift", shift, null)), new float[]{0.25f});
        Node3D a = new Node3D("a", mesh, new Material());
        Node3D b = new Node3D("b", mesh, new Material());
        assertArrayEquals(new float[]{0.25f}, a.getMorphWeights(), "starts at the mesh's defaults");
        b.shareMorphWeights(a);
        a.setMorphWeight(0, 0.75f);
        assertArrayEquals(new float[]{0.75f}, b.getMorphWeights(), "parts that share follow each other");
        assertThrows(IllegalArgumentException.class,
                () -> mesh.setMorphTargets(List.of(new Mesh.MorphTarget("short", new float[3], null)), null));
    }

    @Test
    void aChannelOfWeightsNeedsTheSameCountAtEveryKey() {
        Node3D n = new Node3D("n");
        assertThrows(IllegalArgumentException.class, () -> new AnimationClip.Channel(n, AnimationClip.Path.WEIGHTS,
                new float[]{0, 1}, new float[]{0, 0, 1}, AnimationClip.Interpolation.LINEAR));
        assertDoesNotThrow(() -> new AnimationClip.Channel(n, AnimationClip.Path.WEIGHTS,
                new float[]{0, 1}, new float[]{0, 0, 1, 1}, AnimationClip.Interpolation.LINEAR));
    }
}
