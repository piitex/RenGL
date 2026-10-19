package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sphere around a posed skinned mesh covers the blend shapes too, so a shape that pushes the mesh outward doesn't get
 * it culled while part of it is still on screen.
 */
class SkinBoundsTest {
    private static Mesh skinnedCube() {
        Mesh mesh = Mesh.cube(2f);
        int[] joints = new int[mesh.vertexCount() * 4];
        float[] weights = new float[mesh.vertexCount() * 4];
        for (int v = 0; v < mesh.vertexCount(); v++) weights[v * 4] = 1f;
        return mesh.setSkinning(joints, weights);
    }

    private static float reach(Mesh mesh) {
        Skin skin = new Skin(List.of(new Node3D("joint")), List.of(Mat4.identity()));
        return SkinBounds.reach(mesh, skin)[0];
    }

    @Test
    void aMeshWithoutShapesIsBoundedByItsVertices() {
        assertEquals((float) Math.sqrt(3), reach(skinnedCube()), 1e-4f);
    }

    @Test
    void aShapeThatPushesOutwardWidensTheBound() {
        Mesh mesh = skinnedCube();
        float[] push = new float[mesh.vertexCount() * 3];
        for (int v = 0; v < mesh.vertexCount(); v++) push[v * 3 + 1] = 5f;
        float plain = reach(mesh);
        mesh.setMorphTargets(List.of(new Mesh.MorphTarget("tall", push, null)), null);
        float morphed = reach(mesh);
        assertTrue(morphed >= plain + 4.9f, "the bound grew from " + plain + " to " + morphed);
        // The farthest vertex at full weight is (1, 6, 1) away from the joint.
        assertTrue(morphed >= (float) Math.sqrt(1 + 36 + 1) - 1e-3f, "reach " + morphed);
    }
}
