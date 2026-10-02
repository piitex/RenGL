package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;

import java.util.List;

/**
 * Ray against node meshes. The ray is first tested against each mesh's box in scene space, then against its triangles
 * in the mesh's own space, where the mesh's vertices are, using a hierarchy of boxes built once per mesh so that only the
 * triangles near the ray are tested. An instanced node is tested once per instance and reports its nearest. A skinned
 * mesh is posed on the CPU first, since its vertices only have a place in the scene once its joints have moved them, and
 * so is a mesh with blend shapes mixed in.
 */
final class Picking {
    private Picking() {
    }

    static void cast(Node3D node, Mat4 parentWorld, Ray ray, float maxDistance, List<Hit> out) {
        if (!node.isVisible()) return;
        Mat4 world = parentWorld.mul(node.localMatrix());
        Mesh mesh = node.getMesh();
        if (mesh != null && node.isPickable()) {
            Material material = node.getMaterial();
            boolean frontOnly = material == null || !material.isDoubleSided();
            Hit hit = null;
            float[] morph = node.activeMorph();
            // A morphed mesh has its own shape, so it gets a hierarchy built just for this pick.
            Bvh own = morph == null || (node.getSkin() != null && mesh.isSkinned()) ? null
                    : new Bvh(mesh.morph(morph), mesh.indices(), Mesh.FLOATS_PER_VERTEX);
            if (node.getSkin() != null && mesh.isSkinned()) {
                hit = castSkinned(node, mesh, morph, frontOnly, ray, maxDistance);
            } else if (node.getInstances() != null) {
                Instances instances = node.getInstances();
                float limit = maxDistance;
                float[] data = instances.matrixData();
                for (int i = 0; i < instances.size(); i++) {
                    Mat4 instanceWorld = world.mul(new Mat4(java.util.Arrays.copyOfRange(data, i * 16, i * 16 + 16)));
                    Hit h = castMesh(node, mesh, own, frontOnly, instanceWorld, ray, limit, i);
                    if (h != null) {
                        hit = h;
                        limit = h.distance();
                    }
                }
            } else {
                hit = castMesh(node, mesh, own, frontOnly, world, ray, maxDistance, -1);
            }
            if (hit != null) out.add(hit);
        }
        for (Node3D child : node.getChildren()) cast(child, world, ray, maxDistance, out);
    }

    private static Hit castMesh(Node3D node, Mesh mesh, Bvh own, boolean frontOnly, Mat4 world, Ray ray, float maxDistance, int instance) {
        float broad = ray.distanceTo(mesh.bounds().transform(world));
        if (broad < 0f || broad > maxDistance) return null;
        Mat4 inverse = world.inverse();
        if (inverse == null) return null;

        // Work in the mesh's own space. The direction is left unscaled, so a distance there is the same distance along
        // the original ray.
        Vec3 o = inverse.transformPoint(ray.origin());
        Vec3 d = inverse.transformDirection(ray.direction());
        Bvh.Result result = new Bvh.Result();
        result.distance = maxDistance;
        (own != null ? own : mesh.bvh()).nearest(o.x(), o.y(), o.z(), d.x(), d.y(), d.z(), frontOnly, result);
        if (result.triangle < 0) return null;
        return hit(node, world, ray, result, instance);
    }

    private static Hit hit(Node3D node, Mat4 world, Ray ray, Bvh.Result result, int instance) {
        Vec3 normal = world.normalMatrix().transformDirection(new Vec3(result.nx, result.ny, result.nz)).normalized();
        if (normal.dot(ray.direction()) > 0f) normal = normal.negate();
        return new Hit(node, result.distance * ray.direction().length(), ray.at(result.distance), normal, result.triangle, instance);
    }

    /**
     * Poses the mesh in the scene with the skin's current joints and searches it with a hierarchy built just for this
     * pick: nothing is stored, because the shape changes with every pose.
     */
    private static Hit castSkinned(Node3D node, Mesh mesh, float[] morph, boolean frontOnly, Ray ray, float maxDistance) {
        Skin skin = node.getSkin();
        float[][] joints = new float[skin.joints().size()][];
        for (int i = 0; i < joints.length; i++) joints[i] = skin.jointMatrix(i).toArray();

        float[] v = morph != null ? mesh.morph(morph) : mesh.vertices(); // blend shapes first, then the joints
        int stride = Mesh.FLOATS_PER_VERTEX;
        int count = mesh.vertexCount();
        int[] jointIds = mesh.skinJoints();
        float[] weights = mesh.skinWeights();
        float[] posed = new float[count * 3];
        for (int i = 0; i < count; i++) {
            float px = v[i * stride], py = v[i * stride + 1], pz = v[i * stride + 2];
            float x = 0, y = 0, z = 0;
            for (int k = 0; k < 4; k++) {
                float w = weights[i * 4 + k];
                if (w == 0f) continue;
                float[] m = joints[Math.min(jointIds[i * 4 + k], joints.length - 1)];
                x += w * (m[0] * px + m[4] * py + m[8] * pz + m[12]);
                y += w * (m[1] * px + m[5] * py + m[9] * pz + m[13]);
                z += w * (m[2] * px + m[6] * py + m[10] * pz + m[14]);
            }
            posed[i * 3] = x;
            posed[i * 3 + 1] = y;
            posed[i * 3 + 2] = z;
        }

        Bvh.Result result = new Bvh.Result();
        result.distance = maxDistance;
        Vec3 o = ray.origin(), d = ray.direction();
        int[] idx = mesh.indices();
        // The same hierarchy and triangle test as for any mesh, over the posed positions.
        new Bvh(posed, idx, 3).nearest(o.x(), o.y(), o.z(), d.x(), d.y(), d.z(), frontOnly, result);
        if (result.triangle < 0) return null;
        Vec3 normal = new Vec3(result.nx, result.ny, result.nz).normalized();
        if (normal.dot(d) > 0f) normal = normal.negate();
        return new Hit(node, result.distance * d.length(), ray.at(result.distance), normal, result.triangle, -1);
    }
}
