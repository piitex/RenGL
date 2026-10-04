package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A surface made of triangles, for ground and walls of any form: a level, a terrain, the inside of a cave. It has no
 * inside, so it belongs on static and kinematic bodies; for a moving body use a {@link ConvexHullShape}, or several.
 * Things touch the triangles from either side.
 * <p>
 * Build it from a mesh with {@link #of(Mesh)}, or from a whole loaded model with {@link #of(Node3D)}. Like a hull it
 * stays where its points are, so it lines up with the mesh it came from. The triangles are kept in a tree, so a large
 * level costs little more per contact than a small one.
 * <p>
 * Corners at the same place are joined, so the mesh knows which triangles meet at each edge. An edge between two
 * triangles that are flat or fold inward is a seam rather than a real edge, and nothing catches on it: a box slides
 * across a floor of many triangles as across one.
 */
public final class MeshShape implements Shape {
    /**
     * Neighbouring triangles turned less than this (about 5 degrees) from each other meet at a seam, not an edge.
     */
    private static final float FLAT_COS = 0.996f;

    private final float[] positions; // x, y, z per corner
    private final int[] indices;     // three corners per triangle
    private final Vec3[] normals;    // per triangle, from its winding
    private final byte[] edges;      // per triangle: bit i set when the edge from corner i to corner i + 1 is a real edge
    private final TriangleTree tree;
    private final Bounds bounds;
    private Vec3 lastScale;
    private MeshShape lastScaled;

    /**
     * @param positions x, y, z per corner
     * @param indices   three corners per triangle; triangles with no area are left out
     */
    public MeshShape(float[] positions, int[] indices) {
        if (positions.length % 3 != 0) throw new IllegalArgumentException("positions must hold x, y, z per corner");
        if (indices.length % 3 != 0) throw new IllegalArgumentException("indices must hold three per triangle");
        int corners = positions.length / 3;
        for (float p : positions) if (!Float.isFinite(p)) throw new IllegalArgumentException("A mesh position isn't finite: " + p);
        // Join corners at the same place: meshes split them where normals or texture coordinates change.
        Map<Vec3, Integer> joined = new HashMap<>();
        int[] remap = new int[corners];
        float[] welded = new float[positions.length];
        int count = 0;
        for (int i = 0; i < corners; i++) {
            Vec3 p = new Vec3(positions[i * 3] + 0f, positions[i * 3 + 1] + 0f, positions[i * 3 + 2] + 0f);
            Integer at = joined.get(p);
            if (at == null) {
                at = count++;
                joined.put(p, at);
                welded[at * 3] = p.x();
                welded[at * 3 + 1] = p.y();
                welded[at * 3 + 2] = p.z();
            }
            remap[i] = at;
        }
        this.positions = Arrays.copyOf(welded, count * 3);

        int[] kept = new int[indices.length];
        List<Vec3> faceNormals = new ArrayList<>();
        int triangles = 0;
        for (int t = 0; t < indices.length; t += 3) {
            for (int k = 0; k < 3; k++) {
                if (indices[t + k] < 0 || indices[t + k] >= corners) throw new IllegalArgumentException("index " + indices[t + k] + " is outside the " + corners + " corners");
            }
            int a = remap[indices[t]], b = remap[indices[t + 1]], c = remap[indices[t + 2]];
            if (a == b || b == c || a == c) continue;
            Vec3 n = corner(b).sub(corner(a)).cross(corner(c).sub(corner(a)));
            if (n.lengthSquared() < 1e-20f) continue;
            kept[triangles * 3] = a;
            kept[triangles * 3 + 1] = b;
            kept[triangles * 3 + 2] = c;
            faceNormals.add(n.normalized());
            triangles++;
        }
        if (triangles == 0) throw new IllegalArgumentException("The mesh has no triangles with any area");
        this.indices = Arrays.copyOf(kept, triangles * 3);
        this.normals = faceNormals.toArray(Vec3[]::new);
        this.edges = findEdges();
        this.tree = new TriangleTree(this.positions, this.indices);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i : this.indices) {
            minX = Math.min(minX, this.positions[i * 3]);
            minY = Math.min(minY, this.positions[i * 3 + 1]);
            minZ = Math.min(minZ, this.positions[i * 3 + 2]);
            maxX = Math.max(maxX, this.positions[i * 3]);
            maxY = Math.max(maxY, this.positions[i * 3 + 1]);
            maxZ = Math.max(maxZ, this.positions[i * 3 + 2]);
        }
        bounds = new Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
    }

    /**
     * The mesh's triangles, in its own space. Give it to a body on a node showing that mesh and the two line up, the
     * node's scale included.
     */
    public static MeshShape of(Mesh mesh) {
        float[] v = mesh.vertices();
        float[] positions = new float[mesh.vertexCount() * 3];
        for (int i = 0; i < mesh.vertexCount(); i++) System.arraycopy(v, i * Mesh.FLOATS_PER_VERTEX, positions, i * 3, 3);
        return new MeshShape(positions, mesh.indices());
    }

    /**
     * The triangles of {@code node}'s mesh and of every mesh under it, in {@code node}'s own space: one shape for a whole
     * loaded level. The node's own position, rotation and scale aren't applied, since the body on that node applies
     * them. Hidden nodes count too, so a level can carry simple invisible collision meshes.
     */
    public static MeshShape of(Node3D node) {
        List<float[]> parts = new ArrayList<>();
        List<int[]> partIndices = new ArrayList<>();
        collect(node, null, (positions, indices) -> {
            parts.add(positions);
            partIndices.add(indices);
        });
        if (parts.isEmpty()) throw new IllegalArgumentException(node + " has no meshes to collide with");
        int corners = 0, total = 0;
        for (int i = 0; i < parts.size(); i++) {
            corners += parts.get(i).length;
            total += partIndices.get(i).length;
        }
        float[] positions = new float[corners];
        int[] indices = new int[total];
        int p = 0, q = 0;
        for (int i = 0; i < parts.size(); i++) {
            int base = p / 3;
            System.arraycopy(parts.get(i), 0, positions, p, parts.get(i).length);
            for (int index : partIndices.get(i)) indices[q++] = base + index;
            p += parts.get(i).length;
        }
        return new MeshShape(positions, indices);
    }

    /**
     * Hands {@code visit} the corner positions and triangle indices of each mesh at or under {@code node}, moved into
     * {@code node}'s own space. {@code transform} is the way there from the node being visited, null for the top node.
     */
    static void collect(Node3D node, Mat4 transform, BiConsumer<float[], int[]> visit) {
        Mesh mesh = node.getMesh();
        if (mesh != null) {
            float[] v = mesh.vertices();
            float[] positions = new float[mesh.vertexCount() * 3];
            for (int i = 0; i < mesh.vertexCount(); i++) {
                int o = i * Mesh.FLOATS_PER_VERTEX;
                Vec3 p = new Vec3(v[o], v[o + 1], v[o + 2]);
                if (transform != null) p = transform.transformPoint(p);
                positions[i * 3] = p.x();
                positions[i * 3 + 1] = p.y();
                positions[i * 3 + 2] = p.z();
            }
            visit.accept(positions, mesh.indices());
        }
        for (Node3D child : node.getChildren()) {
            collect(child, transform == null ? child.localMatrix() : transform.mul(child.localMatrix()), visit);
        }
    }

    /**
     * Marks each triangle edge as real or a seam. A real edge is an open border, an edge shared by more than two
     * triangles, or a fold outward sharper than {@link #FLAT_COS}.
     */
    private byte[] findEdges() {
        int triangles = normals.length;
        Map<Long, int[]> shared = new HashMap<>(); // edge -> {triangle, slot, triangle, slot, count}
        for (int t = 0; t < triangles; t++) {
            for (int i = 0; i < 3; i++) {
                int a = indices[t * 3 + i], b = indices[t * 3 + (i + 1) % 3];
                long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
                int[] entry = shared.computeIfAbsent(key, _ -> new int[]{-1, -1, -1, -1, 0});
                if (entry[4] < 2) {
                    entry[entry[4] * 2] = t;
                    entry[entry[4] * 2 + 1] = i;
                }
                entry[4]++;
            }
        }
        byte[] flags = new byte[triangles];
        Arrays.fill(flags, (byte) 7);
        for (int[] entry : shared.values()) {
            if (entry[4] != 2) continue;
            int t1 = entry[0], s1 = entry[1], t2 = entry[2], s2 = entry[3];
            Vec3 n1 = normals[t1], n2 = normals[t2];
            // The corner of the second triangle away from the edge: below the first triangle's plane means a fold outward.
            Vec3 far = corner(indices[t2 * 3 + (s2 + 2) % 3]);
            Vec3 on = corner(indices[t1 * 3 + s1]);
            float height = n1.dot(far.sub(on));
            boolean outward = height < 0f;
            boolean real = n1.dot(n2) < -FLAT_COS || (outward && n1.dot(n2) < FLAT_COS);
            if (!real) {
                flags[t1] &= (byte) ~(1 << s1);
                flags[t2] &= (byte) ~(1 << s2);
            }
        }
        return flags;
    }

    private Vec3 corner(int i) {
        return new Vec3(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
    }

    public int triangleCount() {
        return normals.length;
    }

    /**
     * The three corners of triangle {@code index}, in the shape's own space.
     */
    public Vec3[] triangle(int index) {
        return new Vec3[]{corner(indices[index * 3]), corner(indices[index * 3 + 1]), corner(indices[index * 3 + 2])};
    }

    /**
     * Triangle {@code index}'s normal: the side its corners go counter-clockwise around.
     */
    public Vec3 triangleNormal(int index) {
        return normals[index];
    }

    /**
     * 0: a surface has no volume.
     */
    @Override
    public float volume() {
        return 0f;
    }

    @Override
    public Vec3 unitInertia() {
        return Vec3.ZERO;
    }

    @Override
    public Bounds bounds() {
        return bounds;
    }

    /**
     * The mesh with each corner stretched by {@code scale}. A mirroring scale turns the triangles round so their
     * normals still face out.
     */
    @Override
    public MeshShape scaled(Vec3 scale) {
        if (scale.equals(Vec3.ONE)) return this;
        if (scale.equals(lastScale)) return lastScaled;
        float[] p = positions.clone();
        for (int i = 0; i < p.length; i += 3) {
            p[i] *= scale.x();
            p[i + 1] *= scale.y();
            p[i + 2] *= scale.z();
        }
        int[] idx = indices.clone();
        if (scale.x() * scale.y() * scale.z() < 0f) {
            for (int t = 0; t < idx.length; t += 3) {
                int swap = idx[t + 1];
                idx[t + 1] = idx[t + 2];
                idx[t + 2] = swap;
            }
        }
        MeshShape scaled = new MeshShape(p, idx);
        lastScale = scale;
        lastScaled = scaled;
        return scaled;
    }

    TriangleTree tree() {
        return tree;
    }

    /**
     * Which edges of triangle {@code index} are real edges: bit i for the edge from corner i to corner i + 1.
     */
    int realEdges(int index) {
        return edges[index];
    }

    Vec3 corner(int triangle, int i) {
        return corner(indices[triangle * 3 + i]);
    }

    @Override
    public String toString() {
        return "MeshShape[" + normals.length + " triangles]";
    }
}
