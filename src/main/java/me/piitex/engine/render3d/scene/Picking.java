package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;

import java.util.List;

/**
 * Ray against node meshes. The ray is first tested against each mesh's box in scene space, then against its triangles
 * in the mesh's own space, where the mesh's vertices are. Nothing is cached, so a pick costs time in proportion to the
 * triangles of the meshes whose boxes the ray passes through.
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
            Hit hit = castMesh(node, mesh, material == null || !material.isDoubleSided(), world, ray, maxDistance);
            if (hit != null) out.add(hit);
        }
        for (Node3D child : node.getChildren()) cast(child, world, ray, maxDistance, out);
    }

    private static Hit castMesh(Node3D node, Mesh mesh, boolean frontOnly, Mat4 world, Ray ray, float maxDistance) {
        float broad = ray.distanceTo(mesh.bounds().transform(world));
        if (broad < 0f || broad > maxDistance) return null;
        Mat4 inverse = world.inverse();
        if (inverse == null) return null;

        // Work in the mesh's own space. The direction is left unscaled, so a distance there is the same distance along
        // the original ray.
        Vec3 o = inverse.transformPoint(ray.origin());
        Vec3 d = inverse.transformDirection(ray.direction());
        float ox = o.x(), oy = o.y(), oz = o.z(), dx = d.x(), dy = d.y(), dz = d.z();

        float[] v = mesh.vertices();
        int[] idx = mesh.indices();
        int stride = Mesh.FLOATS_PER_VERTEX;
        float best = maxDistance;
        int bestTriangle = -1;
        float nx = 0, ny = 0, nz = 0;
        for (int t = 0; t < idx.length; t += 3) {
            int a = idx[t] * stride, b = idx[t + 1] * stride, c = idx[t + 2] * stride;
            float e1x = v[b] - v[a], e1y = v[b + 1] - v[a + 1], e1z = v[b + 2] - v[a + 2];
            float e2x = v[c] - v[a], e2y = v[c + 1] - v[a + 1], e2z = v[c + 2] - v[a + 2];
            // Moller-Trumbore. The determinant is positive when the ray comes at the counter-clockwise (front) side.
            float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            float det = e1x * px + e1y * py + e1z * pz;
            if (frontOnly ? det < 1e-20f : Math.abs(det) < 1e-20f) continue;
            float invDet = 1f / det;
            float tx = ox - v[a], ty = oy - v[a + 1], tz = oz - v[a + 2];
            float u = (tx * px + ty * py + tz * pz) * invDet;
            if (u < 0f || u > 1f) continue;
            float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
            float w = (dx * qx + dy * qy + dz * qz) * invDet;
            if (w < 0f || u + w > 1f) continue;
            float distance = (e2x * qx + e2y * qy + e2z * qz) * invDet;
            if (distance < 0f || distance >= best) continue;
            best = distance;
            bestTriangle = t / 3;
            nx = e1y * e2z - e1z * e2y;
            ny = e1z * e2x - e1x * e2z;
            nz = e1x * e2y - e1y * e2x;
        }
        if (bestTriangle < 0) return null;

        Vec3 normal = world.normalMatrix().transformDirection(new Vec3(nx, ny, nz)).normalized();
        if (normal.dot(ray.direction()) > 0f) normal = normal.negate();
        return new Hit(node, best * ray.direction().length(), ray.at(best), normal, bestTriangle);
    }
}
