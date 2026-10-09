package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;

/**
 * A bound for a posed skinned mesh, which has no fixed box: its vertices follow the joints. Every vertex a joint moves
 * stays the same distance from that joint's origin whatever the pose (it is fixed in the joint's own space), so the
 * mesh lies inside the spheres around each joint's origin, as far out as the farthest vertex it moves. Posing the
 * skeleton moves the spheres; the mesh is never outside their union.
 */
final class SkinBounds {
    private SkinBounds() {
    }

    /**
     * For each joint, how far from its origin, in the joint's own space, the farthest vertex it moves lies; -1 for a
     * joint that moves none.
     */
    static float[] reach(Mesh mesh, Skin skin) {
        int joints = skin.joints().size();
        float[] reach = new float[joints];
        java.util.Arrays.fill(reach, -1f);
        int[] ids = mesh.skinJoints();
        float[] weights = mesh.skinWeights();
        float[] v = mesh.vertices();
        Mat4[] inverse = new Mat4[joints];
        for (int i = 0; i < joints; i++) inverse[i] = skin.inverseBind(i);
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            Vec3 p = new Vec3(v[vertex * Mesh.FLOATS_PER_VERTEX], v[vertex * Mesh.FLOATS_PER_VERTEX + 1], v[vertex * Mesh.FLOATS_PER_VERTEX + 2]);
            for (int k = 0; k < 4; k++) {
                if (weights[vertex * 4 + k] <= 1e-6f) continue;
                int joint = ids[vertex * 4 + k];
                if (joint < 0 || joint >= joints) continue;
                reach[joint] = Math.max(reach[joint], inverse[joint].transformPoint(p).length());
            }
        }
        return reach;
    }

    /**
     * A sphere {@code {x, y, z, radius}} in the scene around the mesh in the skeleton's current pose, or null when no
     * joint moves a vertex.
     */
    static float[] sphere(Skin skin, float[] reach) {
        float[] sphere = null;
        for (int i = 0; i < reach.length; i++) {
            if (reach[i] < 0f) continue;
            Mat4 world = skin.joints().get(i).worldMatrix();
            Vec3 origin = world.transformPoint(Vec3.ZERO);
            float stretch = (float) Math.sqrt(Math.max(Math.max(world.transformDirection(Vec3.X).lengthSquared(),
                    world.transformDirection(Vec3.UP).lengthSquared()), world.transformDirection(Vec3.Z).lengthSquared()));
            float[] next = {origin.x(), origin.y(), origin.z(), reach[i] * stretch};
            sphere = sphere == null ? next : SceneRenderer.union(sphere, next);
        }
        return sphere;
    }
}
