package me.piitex.engine.render3d.scene;

import me.piitex.engine.math.Vec2;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;

import java.util.List;

/**
 * Ray against node meshes. The ray is first tested against each mesh's box in scene space, then against its triangles
 * in the mesh's own space, where the mesh's vertices are, using a hierarchy of boxes built once per mesh so that only the
 * triangles near the ray are tested. An instanced node is tested once per instance and reports its nearest. A skinned
 * mesh is posed on the CPU first, since its vertices only have a place in the scene once its joints have moved them, and
 * so is a mesh with blend shapes mixed in. The posed vertices and the hierarchy over them are kept with the pose they
 * were made for (the joints' matrices and the blend weights), so any number of picks while nothing moves share one pass
 * over the vertices, and a pick after a move makes a new one.
 */
final class Picking {
    private Picking() {
    }

    /**
     * How many times a mesh has been posed or morphed for picking, for tests of the cache.
     */
    static final java.util.concurrent.atomic.AtomicInteger POSES = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A mesh as it stood for one pose: what that pose was (the numbers the shape depends on), the vertices it gave and the
     * hierarchy over them.
     */
    private record Posed(Mesh mesh, float[] pose, Bvh bvh) {
    }

    private static final java.util.Map<Node3D, Posed> POSED = new java.util.WeakHashMap<>();

    private static Posed cached(Node3D node, Mesh mesh, float[] pose) {
        synchronized (POSED) {
            Posed posed = POSED.get(node);
            return posed != null && posed.mesh == mesh && java.util.Arrays.equals(posed.pose, pose) ? posed : null;
        }
    }

    private static Posed keep(Node3D node, Posed posed) {
        synchronized (POSED) {
            POSED.put(node, posed);
        }
        return posed;
    }

    static void cast(Node3D node, Mat4 parentWorld, Ray ray, float maxDistance, List<Hit> out) {
        if (!node.isVisible()) return;
        Mat4 world = parentWorld.mul(node.localMatrix());
        Mesh mesh = node.getMesh();
        if (mesh != null && node.isPickable() && !mesh.isLines()) {
            Material material = node.getMaterial();
            boolean frontOnly = material == null || !material.isDoubleSided();
            Hit hit = null;
            float[] morph = node.activeMorph();
            // A morphed mesh has its own shape, so it gets a hierarchy of its own, kept while its weights stay the same.
            Bvh own = morph == null || (node.getSkin() != null && mesh.isSkinned()) ? null : morphed(node, mesh, morph);
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

    private static Bvh morphed(Node3D node, Mesh mesh, float[] weights) {
        Posed posed = cached(node, mesh, weights);
        if (posed == null) {
            POSES.incrementAndGet();
            posed = keep(node, new Posed(mesh, weights.clone(), new Bvh(mesh.morph(weights), mesh.indices(), Mesh.FLOATS_PER_VERTEX)));
        }
        return posed.bvh;
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
        Bvh bvh = own != null ? own : mesh.bvh();
        bvh.nearest(o.x(), o.y(), o.z(), d.x(), d.y(), d.z(), frontOnly, result);
        if (result.triangle < 0) return null;
        // The point on the triangle in the mesh's own space, which is where its corners are.
        Vec2 uv = uv(mesh, bvh, result.triangle, o.x() + d.x() * result.distance, o.y() + d.y() * result.distance, o.z() + d.z() * result.distance);
        return hit(node, world, ray, result, instance, uv);
    }

    private static Hit hit(Node3D node, Mat4 world, Ray ray, Bvh.Result result, int instance, Vec2 uv) {
        Vec3 normal = world.normalMatrix().transformDirection(new Vec3(result.nx, result.ny, result.nz)).normalized();
        if (normal.dot(ray.direction()) > 0f) normal = normal.negate();
        return new Hit(node, result.distance * ray.direction().length(), ray.at(result.distance), normal, result.triangle, instance, uv);
    }

    /**
     * The mesh's texture coordinates at a point on one of its triangles, blended by where the point is between the
     * corners. {@code bvh} holds the corners wherever they were posed, {@code px, py, pz} is in that same space.
     */
    private static Vec2 uv(Mesh mesh, Bvh bvh, int triangle, float px, float py, float pz) {
        float[] weights = bvh.barycentric(triangle, px, py, pz);
        if (weights == null) return null;
        float[] v = mesh.vertices();
        int[] indices = mesh.indices();
        float u = 0f, t = 0f;
        for (int corner = 0; corner < 3; corner++) {
            int o = indices[triangle * 3 + corner] * Mesh.FLOATS_PER_VERTEX;
            u += weights[corner] * v[o + 6];
            t += weights[corner] * v[o + 7];
        }
        return new Vec2(u, t);
    }

    /**
     * Poses the mesh in the scene with the skin's current joints and searches it with a hierarchy over the posed vertices,
     * which is kept for as long as the pose stays the same.
     */
    private static Hit castSkinned(Node3D node, Mesh mesh, float[] morph, boolean frontOnly, Ray ray, float maxDistance) {
        Skin skin = node.getSkin();
        float[][] joints = new float[skin.joints().size()][];
        for (int i = 0; i < joints.length; i++) joints[i] = skin.jointMatrix(i).toArray();
        // What the shape depends on: every joint's matrix, then the blend weights.
        int morphs = morph == null ? 0 : morph.length;
        float[] pose = new float[joints.length * 16 + morphs];
        for (int i = 0; i < joints.length; i++) System.arraycopy(joints[i], 0, pose, i * 16, 16);
        if (morph != null) System.arraycopy(morph, 0, pose, joints.length * 16, morphs);
        Posed posed = cached(node, mesh, pose);
        if (posed == null) posed = keep(node, new Posed(mesh, pose, new Bvh(pose(mesh, morph, joints), mesh.indices(), 3)));

        Bvh.Result result = new Bvh.Result();
        result.distance = maxDistance;
        Vec3 o = ray.origin(), d = ray.direction();
        posed.bvh.nearest(o.x(), o.y(), o.z(), d.x(), d.y(), d.z(), frontOnly, result);
        if (result.triangle < 0) return null;
        Vec3 normal = new Vec3(result.nx, result.ny, result.nz).normalized();
        if (normal.dot(d) > 0f) normal = normal.negate();
        Vec2 uv = uv(mesh, posed.bvh, result.triangle, o.x() + d.x() * result.distance, o.y() + d.y() * result.distance, o.z() + d.z() * result.distance);
        return new Hit(node, result.distance * d.length(), ray.at(result.distance), normal, result.triangle, -1, uv);
    }

    /**
     * The mesh's vertex positions in the scene: blend shapes mixed in first, then each vertex moved by its joints.
     */
    private static float[] pose(Mesh mesh, float[] morph, float[][] joints) {
        POSES.incrementAndGet();
        float[] v = morph != null ? mesh.morph(morph) : mesh.vertices();
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
        return posed;
    }
}
