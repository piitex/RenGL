package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Posed and morphed meshes are posed once per pose for picking, however many picks there are.
 */
class PickingCacheTest {
    private static final Vec3 FORWARD = new Vec3(0, 0, -1);

    private static Node3D skinnedCube(Scene3D scene, Node3D joint) {
        Mesh mesh = Mesh.cube(2f);
        int[] joints = new int[mesh.vertexCount() * 4];
        float[] weights = new float[mesh.vertexCount() * 4];
        for (int v = 0; v < mesh.vertexCount(); v++) weights[v * 4] = 1f;
        mesh.setSkinning(joints, weights);
        Node3D body = new Node3D("body", mesh, new Material(Color.RED));
        body.setSkin(new Skin(List.of(joint), List.of(Mat4.identity())));
        scene.add(joint);
        scene.add(body);
        return body;
    }

    @Test
    void picksOfOnePoseShareOnePass() {
        Scene3D scene = new Scene3D();
        Node3D joint = new Node3D("joint");
        joint.setPosition(0, 0, -5);
        Node3D body = skinnedCube(scene, joint);

        int before = Picking.POSES.get();
        for (int i = 0; i < 20; i++) {
            Hit hit = scene.pick(Ray.of(new Vec3(0.01f * i, 0, 0), FORWARD));
            assertNotNull(hit, "pick " + i);
            assertSame(body, hit.node());
            assertEquals(4f, hit.distance(), 1e-3f);
        }
        assertEquals(1, Picking.POSES.get() - before, "twenty picks, one pose");
    }

    @Test
    void movingAJointPosesAgainAndFollowsIt() {
        Scene3D scene = new Scene3D();
        Node3D joint = new Node3D("joint");
        joint.setPosition(0, 0, -5);
        skinnedCube(scene, joint);
        assertNotNull(scene.pick(Ray.of(Vec3.ZERO, FORWARD)));
        int posed = Picking.POSES.get();

        joint.setPosition(10, 0, -5);
        assertNull(scene.pick(Ray.of(Vec3.ZERO, FORWARD)), "the cube moved away with its joint");
        assertEquals(1, Picking.POSES.get() - posed);
        Hit hit = scene.pick(Ray.of(new Vec3(10, 0, 0), FORWARD));
        assertNotNull(hit);
        assertEquals(1, Picking.POSES.get() - posed, "and that pose is kept");
        assertEquals(4f, hit.distance(), 1e-3f);
    }

    @Test
    void twoSkinnedNodesKeepTheirOwnPoses() {
        Scene3D scene = new Scene3D();
        Node3D jointA = new Node3D("a"), jointB = new Node3D("b");
        jointA.setPosition(-5, 0, -5);
        jointB.setPosition(5, 0, -5);
        skinnedCube(scene, jointA);
        skinnedCube(scene, jointB);
        int before = Picking.POSES.get();
        for (int i = 0; i < 5; i++) {
            assertNotNull(scene.pick(Ray.of(new Vec3(-5, 0, 0), FORWARD)));
            assertNotNull(scene.pick(Ray.of(new Vec3(5, 0, 0), FORWARD)));
        }
        assertEquals(2, Picking.POSES.get() - before, "one pose each, not one per pick");
    }

    @Test
    void morphedMeshesAreMixedOncePerWeights() throws Exception {
        Model model = Model.load(Path.of("src/test/resources/models/morph.gltf"));
        Scene3D scene = new Scene3D();
        Node3D face = model.root().find("face");
        scene.add(model.root());
        face.setMorphWeights(1f, 0f);
        int before = Picking.POSES.get();
        Ray down = new Ray(new Vec3(0, 5, -1), new Vec3(0, -1, 0));
        for (int i = 0; i < 10; i++) {
            Hit hit = scene.pick(down);
            assertNotNull(hit);
            assertEquals(4f, hit.distance(), 1e-3f, "the quad is lifted by 1");
        }
        assertEquals(1, Picking.POSES.get() - before);
        face.setMorphWeights(0.5f, 0f);
        Hit half = scene.pick(down);
        assertNotNull(half);
        assertEquals(4.5f, half.distance(), 1e-3f);
        assertEquals(2, Picking.POSES.get() - before, "new weights, a new mix");
        face.setMorphWeights(0f, 0f);
        assertEquals(5f, scene.pick(down).distance(), 1e-3f, "no weights is the plain mesh, which needs no mixing");
        assertEquals(2, Picking.POSES.get() - before);
    }
}
