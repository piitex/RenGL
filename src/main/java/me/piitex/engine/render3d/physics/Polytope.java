package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A convex solid with flat faces, as the narrowphase sees hulls, boxes and mesh triangles: corners, faces as loops of
 * corners with their outward normals, and edges. A triangle is a flat polytope with two faces, one each way.
 */
final class Polytope {
    final Vec3[] vertices;
    final int[][] faces;     // corner loops, counter-clockwise seen from outside
    final Vec3[] normals;    // outward unit normal per face
    final float[] offsets;   // normal . x for any x on the face
    final int[] edges;       // two corners per edge, each edge once
    final int[] edgeDirection; // which of the directions each edge runs along
    final Vec3[] directions; // unit edge directions, parallel ones merged: the edge axes to test

    private Polytope(Vec3[] vertices, int[][] faces, Vec3[] normals, float[] offsets, int[] edges, int[] edgeDirection, Vec3[] directions) {
        this.vertices = vertices;
        this.faces = faces;
        this.normals = normals;
        this.offsets = offsets;
        this.edges = edges;
        this.edgeDirection = edgeDirection;
        this.directions = directions;
    }

    /**
     * Builds the normals, offsets and edges from corners and faces.
     */
    static Polytope of(Vec3[] vertices, int[][] faces) {
        Vec3[] normals = new Vec3[faces.length];
        float[] offsets = new float[faces.length];
        for (int f = 0; f < faces.length; f++) {
            int[] loop = faces[f];
            // Newell's method: steady for any planar loop, whatever its first corners are.
            float nx = 0, ny = 0, nz = 0;
            for (int i = 0; i < loop.length; i++) {
                Vec3 p = vertices[loop[i]], q = vertices[loop[(i + 1) % loop.length]];
                nx += (p.y() - q.y()) * (p.z() + q.z());
                ny += (p.z() - q.z()) * (p.x() + q.x());
                nz += (p.x() - q.x()) * (p.y() + q.y());
            }
            Vec3 n = new Vec3(nx, ny, nz).normalized();
            float offset = Float.NEGATIVE_INFINITY;
            for (int index : loop) offset = Math.max(offset, n.dot(vertices[index]));
            normals[f] = n;
            offsets[f] = offset;
        }
        List<int[]> edgeList = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int[] loop : faces) {
            for (int i = 0; i < loop.length; i++) {
                int a = loop[i], b = loop[(i + 1) % loop.length];
                long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
                if (seen.add(key)) edgeList.add(new int[]{a, b});
            }
        }
        int[] edges = new int[edgeList.size() * 2];
        int[] edgeDirection = new int[edgeList.size()];
        List<Vec3> directions = new ArrayList<>();
        for (int e = 0; e < edgeList.size(); e++) {
            int a = edgeList.get(e)[0], b = edgeList.get(e)[1];
            edges[e * 2] = a;
            edges[e * 2 + 1] = b;
            Vec3 d = vertices[b].sub(vertices[a]).normalized();
            int match = -1;
            for (int k = 0; k < directions.size() && match < 0; k++) if (directions.get(k).cross(d).lengthSquared() < 1e-8f) match = k;
            if (match < 0) {
                match = directions.size();
                directions.add(d);
            }
            edgeDirection[e] = match;
        }
        return new Polytope(vertices, faces, normals, offsets, edges, edgeDirection, directions.toArray(Vec3[]::new));
    }

    static Polytope box(Vec3 half) {
        Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vec3((i & 1) == 0 ? -half.x() : half.x(), (i & 2) == 0 ? -half.y() : half.y(), (i & 4) == 0 ? -half.z() : half.z());
        }
        int[][] faces = {{0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}};
        return of(corners, faces);
    }

    private static final int[][] TRIANGLE_FACES = {{0, 1, 2}, {0, 2, 1}};
    private static final int[] TRIANGLE_EDGES = {0, 1, 1, 2, 2, 0};
    private static final int[] TRIANGLE_EDGE_DIRECTIONS = {0, 1, 2};

    /**
     * A triangle with a face each way, so things touch it from either side. {@code normal} is the side its corners go
     * counter-clockwise around.
     */
    static Polytope triangle(Vec3 a, Vec3 b, Vec3 c, Vec3 normal) {
        float offset = normal.dot(a);
        return new Polytope(new Vec3[]{a, b, c}, TRIANGLE_FACES, new Vec3[]{normal, normal.negate()}, new float[]{offset, -offset},
                TRIANGLE_EDGES, TRIANGLE_EDGE_DIRECTIONS, new Vec3[]{b.sub(a).normalized(), c.sub(b).normalized(), a.sub(c).normalized()});
    }

    /**
     * The same polytope moved by {@code pose}. Faces and edges are shared; corners, normals and directions are new.
     */
    Polytope placed(Pose pose) {
        Vec3[] v = new Vec3[vertices.length];
        for (int i = 0; i < v.length; i++) v[i] = pose.toWorld(vertices[i]);
        Vec3[] n = new Vec3[normals.length];
        float[] o = new float[normals.length];
        for (int f = 0; f < n.length; f++) {
            n[f] = pose.rotate(normals[f]);
            o[f] = offsets[f] + n[f].dot(pose.position());
        }
        Vec3[] d = new Vec3[directions.length];
        for (int k = 0; k < d.length; k++) d[k] = pose.rotate(directions[k]);
        return new Polytope(v, faces, n, o, edges, edgeDirection, d);
    }

    float min(Vec3 axis) {
        float m = Float.POSITIVE_INFINITY;
        for (Vec3 v : vertices) m = Math.min(m, v.dot(axis));
        return m;
    }

    float max(Vec3 axis) {
        float m = Float.NEGATIVE_INFINITY;
        for (Vec3 v : vertices) m = Math.max(m, v.dot(axis));
        return m;
    }

    /**
     * The face whose normal points most along {@code direction}.
     */
    int faceToward(Vec3 direction) {
        int best = 0;
        float most = Float.NEGATIVE_INFINITY;
        for (int f = 0; f < normals.length; f++) {
            float d = normals[f].dot(direction);
            if (d > most) {
                most = d;
                best = f;
            }
        }
        return best;
    }
}
