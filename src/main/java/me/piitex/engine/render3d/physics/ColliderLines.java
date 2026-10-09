package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Mesh;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The outlines of collision shapes for {@link ColliderView}, as meshes of real line segments: the twelve edges of a box,
 * three circles round a sphere, the rings and rods of a capsule, every edge of a hull, the real edges of a mesh (not the
 * seams between its flat triangles) and a grid for the endless plane. Each outline sits a few millimetres outside its shape,
 * like the surface it goes with.
 */
final class ColliderLines {
    private static final float SKIN = ColliderMeshes.SKIN;
    private static final int CIRCLE = 32, ARC = 16;
    private static final float PLANE_SIZE = 100f, PLANE_CELL = 2f;

    private float[] positions = new float[3 * 256];
    private int[] segments = new int[2 * 256];
    private int vertices, segmentCount;

    private ColliderLines() {
    }

    static Mesh of(Shape shape) {
        ColliderLines b = new ColliderLines();
        switch (shape) {
            case BoxShape box -> b.box(box.halfExtents().add(new Vec3(SKIN, SKIN, SKIN)));
            case SphereShape sphere -> b.sphere(sphere.radius() + SKIN);
            case CapsuleShape capsule -> b.capsule(capsule.radius() + SKIN, capsule.halfHeight());
            case ConvexHullShape hull -> b.hull(hull.polytope());
            case MeshShape mesh -> b.mesh(mesh);
            case PlaneShape plane -> b.plane(plane.normal());
        }
        return Mesh.lines(Arrays.copyOf(b.positions, b.vertices * 3), Arrays.copyOf(b.segments, b.segmentCount * 2));
    }

    private void box(Vec3 h) {
        Vec3[] c = new Vec3[8];
        for (int i = 0; i < 8; i++) c[i] = new Vec3((i & 1) == 0 ? -h.x() : h.x(), (i & 2) == 0 ? -h.y() : h.y(), (i & 4) == 0 ? -h.z() : h.z());
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit < 8; bit <<= 1) if ((i & bit) == 0) line(c[i], c[i | bit]);
        }
    }

    private void sphere(float r) {
        circle(Vec3.ZERO, Vec3.X, Vec3.UP, r, CIRCLE);
        circle(Vec3.ZERO, Vec3.X, Vec3.Z, r, CIRCLE);
        circle(Vec3.ZERO, Vec3.UP, Vec3.Z, r, CIRCLE);
    }

    private void capsule(float r, float halfHeight) {
        Vec3 top = new Vec3(0f, halfHeight, 0f), bottom = new Vec3(0f, -halfHeight, 0f);
        circle(top, Vec3.X, Vec3.Z, r, CIRCLE);
        circle(bottom, Vec3.X, Vec3.Z, r, CIRCLE);
        Vec3[] around = {new Vec3(r, 0f, 0f), new Vec3(-r, 0f, 0f), new Vec3(0f, 0f, r), new Vec3(0f, 0f, -r)};
        for (Vec3 side : around) line(top.add(side), bottom.add(side));
        // The caps: half circles over the top and under the bottom, in the XY and ZY planes.
        arc(top, Vec3.X, Vec3.UP, r, 1f);
        arc(top, Vec3.Z, Vec3.UP, r, 1f);
        arc(bottom, Vec3.X, Vec3.UP, r, -1f);
        arc(bottom, Vec3.Z, Vec3.UP, r, -1f);
    }

    private void hull(Polytope hull) {
        Vec3 middle = Vec3.ZERO;
        for (Vec3 v : hull.vertices) middle = middle.add(v);
        middle = middle.mul(1f / hull.vertices.length);
        Vec3[] grown = new Vec3[hull.vertices.length];
        for (int i = 0; i < grown.length; i++) {
            Vec3 out = hull.vertices[i].sub(middle);
            float length = out.length();
            grown[i] = length > 1e-6f ? hull.vertices[i].add(out.mul(SKIN / length)) : hull.vertices[i];
        }
        for (int e = 0; e < hull.edges.length / 2; e++) line(grown[hull.edges[e * 2]], grown[hull.edges[e * 2 + 1]]);
    }

    /**
     * Each edge of a triangle mesh that is real (a border, or a fold sharper than a seam), once however many triangles share it.
     */
    private void mesh(MeshShape mesh) {
        record Edge(Vec3 a, Vec3 b) {
            static Edge of(Vec3 p, Vec3 q) {
                boolean first = p.x() != q.x() ? p.x() < q.x() : p.y() != q.y() ? p.y() < q.y() : p.z() < q.z();
                return first ? new Edge(p, q) : new Edge(q, p);
            }
        }
        Set<Edge> seen = new HashSet<>();
        for (int t = 0; t < mesh.triangleCount(); t++) {
            Vec3[] c = mesh.triangle(t);
            Vec3 lift = mesh.triangleNormal(t).mul(SKIN);
            int real = mesh.realEdges(t);
            for (int i = 0; i < 3; i++) {
                if ((real & 1 << i) == 0) continue;
                Vec3 p = c[i], q = c[(i + 1) % 3];
                if (seen.add(Edge.of(p, q))) line(p.add(lift), q.add(lift));
            }
        }
    }

    private void plane(Vec3 normal) {
        Vec3 n = normal.normalized();
        Vec3[] side = Joint.perpendiculars(n);
        Vec3 u = side[0], v = side[1], lift = n.mul(SKIN);
        float half = PLANE_SIZE / 2f;
        int lines = Math.round(PLANE_SIZE / PLANE_CELL);
        for (int i = 0; i <= lines; i++) {
            float at = -half + i * PLANE_CELL;
            line(lift.add(u.mul(at)).sub(v.mul(half)), lift.add(u.mul(at)).add(v.mul(half)));
            line(lift.add(v.mul(at)).sub(u.mul(half)), lift.add(v.mul(at)).add(u.mul(half)));
        }
    }

    // ---- Pieces -----------------------------------------------------------------------------------------------------

    private void circle(Vec3 center, Vec3 a, Vec3 b, float radius, int steps) {
        Vec3 previous = center.add(a.mul(radius));
        for (int i = 1; i <= steps; i++) {
            double angle = 2 * Math.PI * i / steps;
            Vec3 next = center.add(a.mul((float) Math.cos(angle) * radius)).add(b.mul((float) Math.sin(angle) * radius));
            line(previous, next);
            previous = next;
        }
    }

    /**
     * Half a circle from {@code a * radius} over the {@code up * sign} side to {@code -a * radius}.
     */
    private void arc(Vec3 center, Vec3 a, Vec3 up, float radius, float sign) {
        Vec3 previous = center.add(a.mul(radius));
        for (int i = 1; i <= ARC; i++) {
            double angle = Math.PI * i / ARC;
            Vec3 next = center.add(a.mul((float) Math.cos(angle) * radius)).add(up.mul(sign * (float) Math.sin(angle) * radius));
            line(previous, next);
            previous = next;
        }
    }

    private void line(Vec3 p, Vec3 q) {
        if (vertices + 2 > positions.length / 3) positions = Arrays.copyOf(positions, positions.length * 2);
        if (segmentCount + 1 > segments.length / 2) segments = Arrays.copyOf(segments, segments.length * 2);
        int i = vertices * 3;
        positions[i] = p.x();
        positions[i + 1] = p.y();
        positions[i + 2] = p.z();
        positions[i + 3] = q.x();
        positions[i + 4] = q.y();
        positions[i + 5] = q.z();
        segments[segmentCount * 2] = vertices;
        segments[segmentCount * 2 + 1] = vertices + 1;
        vertices += 2;
        segmentCount++;
    }
}
