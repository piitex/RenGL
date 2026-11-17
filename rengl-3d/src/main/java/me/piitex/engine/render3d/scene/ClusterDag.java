package me.piitex.engine.render3d.scene;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.meshoptimizer.MeshoptMeshlet;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.util.meshoptimizer.MeshOptimizer.*;

/**
 * A mesh as a graph of small clusters of triangles at many levels of detail at once, the way virtual geometry (Nanite and
 * the like) draws it: each part of the mesh can be drawn at the level its own distance asks for, with no cracks between
 * parts at different levels.
 * <p>
 * The finest clusters split the mesh into pieces of at most {@link #MAX_VERTICES} vertices and {@link #MAX_TRIANGLES}
 * triangles. Then, over and over, neighbouring clusters are grouped by fours, each group's triangles are simplified to half
 * with the group's outline held in place, and the result is split into clusters again: a coarser level whose clusters share
 * their outline with the clusters around them. Every cluster keeps the error of its own level and the error of the level
 * that replaces it (its group's), each with a sphere that holds it; both only grow going up. So the clusters to draw for a
 * view are exactly those whose own error is small enough on screen and whose replacement's is not ({@link #cut}): the
 * choice for each cluster is made alone, and it still makes a mesh with no hole and no overlap.
 * <p>
 * Every cluster indexes the mesh's own vertices (simplifying only drops vertices), so one vertex buffer serves all levels.
 */
final class ClusterDag {
    static final int MAX_VERTICES = 64, MAX_TRIANGLES = 124;
    /** Clusters grouped and simplified together. */
    static final int GROUP_SIZE = 4;
    /** A group that can't be simplified below this share of its triangles is left as the top of its part of the graph. */
    private static final float LEAST_GAIN = 0.85f;
    private static final int STRIDE = Mesh.FLOATS_PER_VERTEX * 4;

    final Mesh mesh;
    /** Every cluster's triangles, three of the mesh's vertex numbers each, one cluster after the other. */
    final int[] indices;
    /** Where each cluster's triangles start in {@link #indices} (in indices), and how many indices it has. */
    final int[] first, count;
    /** Each cluster's level: 0 for the finest. */
    final int[] level;
    /** Each cluster's error, in the mesh's units, and a sphere {x, y, z, radius} around what it stands for. */
    final float[] error, sphere;
    /** The same for what replaces the cluster one level up; infinite error for a cluster at the top. */
    final float[] parentError, parentSphere;
    /**
     * A sphere around the cluster's own triangles, tighter than {@link #sphere} (which a cluster made from a group shares with
     * the group, so the choice of level agrees across it): what is tested against the view and against what hides it.
     */
    final float[] bounds;
    /** The hierarchy over the clusters, which the GPU walks to skip whole parts of the graph. */
    final Tree tree;
    /** The errors of the {@link #flat} cuts, and the cuts made so far; built on first use. */
    private float[] flatErrors;
    private Mesh[] flats;

    private ClusterDag(Mesh mesh, int[] indices, int[] first, int[] count, int[] level, float[] error, float[] sphere,
                       float[] parentError, float[] parentSphere, float[] bounds, Tree tree) {
        this.tree = tree;
        this.mesh = mesh;
        this.indices = indices;
        this.first = first;
        this.count = count;
        this.level = level;
        this.error = error;
        this.sphere = sphere;
        this.parentError = parentError;
        this.parentSphere = parentSphere;
        this.bounds = bounds;
    }

    int size() {
        return first.length;
    }

    int levels() {
        int most = 0;
        for (int l : level) most = Math.max(most, l);
        return most + 1;
    }

    /** One cluster while the graph is built. */
    private static final class Cluster {
        int[] triangles;
        int level;
        float error;
        float[] sphere;
        float parentError = Float.POSITIVE_INFINITY;
        float[] parentSphere;
        float[] bounds;
    }

    /**
     * Builds the graph of a mesh of triangles. A few milliseconds per ten thousand triangles; touches nothing but the mesh.
     */
    static ClusterDag build(Mesh mesh) {
        if (mesh.isLines()) throw new IllegalArgumentException("A mesh of lines has no clusters");
        float[] v = mesh.vertices();
        int vertexCount = mesh.vertexCount();
        FloatBuffer positions = MemoryUtil.memAllocFloat(v.length);
        try {
            positions.put(v).flip();
            List<Cluster> all = new ArrayList<>();
            List<Cluster> current = split(mesh.indices(), positions, vertexCount, 0, 0f, null);
            all.addAll(current);
            int level = 0;
            while (current.size() > 1) {
                level++;
                List<Cluster> next = new ArrayList<>();
                for (List<Cluster> group : groups(current, positions, vertexCount)) {
                    int total = 0;
                    for (Cluster c : group) total += c.triangles.length;
                    int[] merged = new int[total];
                    int at = 0;
                    float childError = 0f;
                    float[] bound = null;
                    for (Cluster c : group) {
                        System.arraycopy(c.triangles, 0, merged, at, c.triangles.length);
                        at += c.triangles.length;
                        childError = Math.max(childError, c.error);
                        bound = bound == null ? c.sphere : SceneRenderer.union(bound, c.sphere);
                    }
                    Simplified simpler = group.size() < 2 ? null : simplify(merged, positions, vertexCount);
                    if (simpler == null || simpler.triangles.length > merged.length * LEAST_GAIN) continue; // the top of this part
                    // The group's error counts its children's: what it stands for is the full mesh, not its children.
                    float groupError = childError + simpler.error;
                    for (Cluster c : group) {
                        c.parentError = groupError;
                        c.parentSphere = bound;
                    }
                    next.addAll(split(simpler.triangles, positions, vertexCount, level, groupError, bound));
                }
                if (next.isEmpty()) break;
                // Clusters whose group was left alone stay on top of their part; the new ones carry on.
                all.addAll(next);
                current = next;
            }
            return pack(mesh, all);
        } finally {
            MemoryUtil.memFree(positions);
        }
    }

    /** A group's triangles after simplifying, and how far they are from before, in the mesh's units. */
    private record Simplified(int[] triangles, float error) {
    }

    /**
     * The group's triangles simplified to half with its outline locked (the edges it shares with clusters outside the group,
     * and the mesh's own open edges), or null when nothing could be taken away.
     */
    private static Simplified simplify(int[] triangles, FloatBuffer positions, int vertexCount) {
        IntBuffer in = MemoryUtil.memAllocInt(triangles.length), out = MemoryUtil.memAllocInt(triangles.length);
        FloatBuffer error = MemoryUtil.memAllocFloat(1);
        try {
            in.put(triangles).flip();
            long target = Math.max(3, (triangles.length / 6) * 3L);
            long count = meshopt_simplify(out, in, positions, vertexCount, STRIDE, target, Float.MAX_VALUE,
                    meshopt_SimplifyLockBorder | meshopt_SimplifySparse | meshopt_SimplifyErrorAbsolute, error);
            if (count == 0 || count >= triangles.length) return null;
            int[] result = new int[(int) count];
            out.get(0, result);
            return new Simplified(result, error.get(0));
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
            MemoryUtil.memFree(error);
        }
    }

    /**
     * The triangles split into clusters of at most {@link #MAX_VERTICES} vertices and {@link #MAX_TRIANGLES} triangles, near
     * ones together. The clusters of a group (and the finest ones) get their own spheres; those made from a group share its.
     */
    private static List<Cluster> split(int[] triangles, FloatBuffer positions, int vertexCount, int level, float error, float[] groupSphere) {
        long most = meshopt_buildMeshletsBound(triangles.length, MAX_VERTICES, MAX_TRIANGLES);
        MeshoptMeshlet.Buffer meshlets = MeshoptMeshlet.malloc((int) most);
        IntBuffer meshletVertices = MemoryUtil.memAllocInt((int) (most * MAX_VERTICES));
        ByteBuffer meshletTriangles = MemoryUtil.memAlloc((int) (most * MAX_TRIANGLES * 3));
        IntBuffer in = MemoryUtil.memAllocInt(triangles.length);
        List<Cluster> out = new ArrayList<>();
        try {
            in.put(triangles).flip();
            long made = meshopt_buildMeshlets(meshlets, meshletVertices, meshletTriangles, in, positions, vertexCount, STRIDE,
                    MAX_VERTICES, MAX_TRIANGLES, 0f);
            for (int m = 0; m < made; m++) {
                MeshoptMeshlet meshlet = meshlets.get(m);
                int vOffset = meshlet.vertex_offset(), tOffset = meshlet.triangle_offset(), tCount = meshlet.triangle_count();
                Cluster c = new Cluster();
                c.triangles = new int[tCount * 3];
                for (int k = 0; k < tCount * 3; k++) {
                    c.triangles[k] = meshletVertices.get(vOffset + (meshletTriangles.get(tOffset + k) & 0xFF));
                }
                c.level = level;
                c.error = error;
                c.bounds = sphereOf(c.triangles, positions);
                c.sphere = groupSphere != null ? groupSphere : c.bounds;
                out.add(c);
            }
        } finally {
            meshlets.free();
            MemoryUtil.memFree(meshletVertices);
            MemoryUtil.memFree(meshletTriangles);
            MemoryUtil.memFree(in);
        }
        return out;
    }

    /** Clusters grouped by {@link #GROUP_SIZE} with their neighbours (meshoptimizer's partition, which looks at shared vertices). */
    private static List<List<Cluster>> groups(List<Cluster> clusters, FloatBuffer positions, int vertexCount) {
        int total = 0;
        for (Cluster c : clusters) total += c.triangles.length;
        IntBuffer clusterIndices = MemoryUtil.memAllocInt(total), counts = MemoryUtil.memAllocInt(clusters.size());
        IntBuffer partition = MemoryUtil.memAllocInt(clusters.size());
        try {
            for (Cluster c : clusters) {
                clusterIndices.put(c.triangles);
                counts.put(c.triangles.length);
            }
            clusterIndices.flip();
            counts.flip();
            int made = (int) meshopt_partitionClusters(partition, clusterIndices, counts, positions, vertexCount, STRIDE, GROUP_SIZE);
            List<List<Cluster>> groups = new ArrayList<>();
            for (int g = 0; g < made; g++) groups.add(new ArrayList<>());
            for (int i = 0; i < clusters.size(); i++) groups.get(partition.get(i)).add(clusters.get(i));
            groups.removeIf(List::isEmpty);
            return groups;
        } finally {
            MemoryUtil.memFree(clusterIndices);
            MemoryUtil.memFree(counts);
            MemoryUtil.memFree(partition);
        }
    }

    /** A sphere around the triangles' corners: the middle of their box, out to the farthest corner. */
    private static float[] sphereOf(int[] triangles, FloatBuffer p) {
        float lx = Float.MAX_VALUE, ly = Float.MAX_VALUE, lz = Float.MAX_VALUE, hx = -Float.MAX_VALUE, hy = -Float.MAX_VALUE, hz = -Float.MAX_VALUE;
        for (int i : triangles) {
            int o = i * Mesh.FLOATS_PER_VERTEX;
            lx = Math.min(lx, p.get(o));
            hx = Math.max(hx, p.get(o));
            ly = Math.min(ly, p.get(o + 1));
            hy = Math.max(hy, p.get(o + 1));
            lz = Math.min(lz, p.get(o + 2));
            hz = Math.max(hz, p.get(o + 2));
        }
        float cx = (lx + hx) / 2f, cy = (ly + hy) / 2f, cz = (lz + hz) / 2f, r = 0f;
        for (int i : triangles) {
            int o = i * Mesh.FLOATS_PER_VERTEX;
            float dx = p.get(o) - cx, dy = p.get(o + 1) - cy, dz = p.get(o + 2) - cz;
            r = Math.max(r, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return new float[]{cx, cy, cz, r};
    }

    /** One node of the hierarchy while it is built: a leaf holds clusters, any other node holds nodes. */
    private static final class Node {
        final List<Node> children = new ArrayList<>();
        List<Cluster> clusters;
        float[] bounds, lodSphere, ownSphere;
        float minError = Float.POSITIVE_INFINITY, maxParentError = 0f;
        int depth, first, underFirst, underCount;

        void take(float[] bounds, float[] lodSphere, float[] ownSphere, float minError, float maxParentError) {
            this.bounds = this.bounds == null ? bounds : SceneRenderer.union(this.bounds, bounds);
            this.lodSphere = this.lodSphere == null ? lodSphere : SceneRenderer.union(this.lodSphere, lodSphere);
            this.ownSphere = this.ownSphere == null ? ownSphere : SceneRenderer.union(this.ownSphere, ownSphere);
            this.minError = Math.min(this.minError, minError);
            this.maxParentError = Math.max(this.maxParentError, maxParentError);
        }

        /** Each sphere made a little bigger, so it holds what is under it after rounding too (and a copy, never a cluster's). */
        void seal() {
            bounds = padded(bounds);
            lodSphere = padded(lodSphere);
            ownSphere = padded(ownSphere);
        }
    }

    /** A node over the clusters: a leaf when they are few, else up to {@link #FAN_OUT} children, each a part of the space. */
    private static Node node(List<Cluster> clusters) {
        Node n = new Node();
        if (clusters.size() <= LEAF_CLUSTERS) {
            n.clusters = clusters;
            for (Cluster c : clusters) n.take(c.bounds, c.parentSphere != null ? c.parentSphere : c.sphere, c.sphere, c.error, c.parentError);
            n.seal();
            return n;
        }
        List<List<Cluster>> parts = List.of(clusters);
        // Halved three times (two, four, then eight parts), each part along the way its clusters are most spread.
        for (int step = 0; step < 3; step++) {
            List<List<Cluster>> next = new ArrayList<>();
            for (List<Cluster> part : parts) {
                if (part.size() <= LEAF_CLUSTERS) {
                    next.add(part);
                    continue;
                }
                float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                for (Cluster c : part) {
                    for (int k = 0; k < 3; k++) {
                        lo[k] = Math.min(lo[k], c.bounds[k]);
                        hi[k] = Math.max(hi[k], c.bounds[k]);
                    }
                }
                int axis = hi[0] - lo[0] >= hi[1] - lo[1] && hi[0] - lo[0] >= hi[2] - lo[2] ? 0 : hi[1] - lo[1] >= hi[2] - lo[2] ? 1 : 2;
                List<Cluster> sorted = new ArrayList<>(part);
                sorted.sort((a, b) -> Float.compare(a.bounds[axis], b.bounds[axis]));
                next.add(sorted.subList(0, sorted.size() / 2));
                next.add(sorted.subList(sorted.size() / 2, sorted.size()));
            }
            parts = next;
        }
        for (List<Cluster> part : parts) {
            Node child = node(part);
            n.children.add(child);
            n.take(child.bounds, child.lodSphere, child.ownSphere, child.minError, child.maxParentError);
        }
        n.seal();
        return n;
    }

    private static float[] padded(float[] s) {
        return new float[]{s[0], s[1], s[2], s[3] + 1e-5f * (s[3] + Math.abs(s[0]) + Math.abs(s[1]) + Math.abs(s[2]))};
    }

    /**
     * The hierarchy over the clusters, for the GPU to skip whole parts of the graph: under one root per level, a tree whose
     * nodes split their clusters by place, {@link #FAN_OUT} ways, down to leaves of at most {@link #LEAF_CLUSTERS}. Nodes are
     * in breadth-first order, so each node's children are side by side (nodes for a node, clusters for a leaf). The clusters
     * are in the order of a walk down each root, so all the clusters under a node are side by side too: {@code under} holds,
     * for each node, the first of them and how many. Each node has spheres around what is under it (the clusters' own triangles,
     * their replacements, their own spheres) and the least own error and the greatest replacement error under it.
     * <p>
     * A node can be skipped when even its nearest point shows the greatest replacement error under it as fine enough (every
     * cluster under it is replaced), or when even its farthest point shows the least own error as too coarse (every cluster
     * under it is too coarse), or, for the camera, when it is out of view.
     */
    record Tree(int[] first, int[] count, boolean[] leaf, int[] under, float[] bounds, float[] lodSphere, float[] ownSphere,
                float[] minError, float[] maxParentError, int roots, int depths, int widest) {
        int size() {
            return first.length;
        }
    }

    /** Clusters in a leaf of the {@link Tree} at most, and children of one of its nodes at most. */
    static final int LEAF_CLUSTERS = 16, FAN_OUT = 8;
    /**
     * How far inside its limit a node's test must be to skip it, so rounding can't skip a cluster the cluster's own test
     * draws: a node is replaced when its error is under this share of the limit, too coarse when over the limit by this much.
     */
    static final float FINE_MARGIN = 0.9999f, COARSE_MARGIN = 1.0001f;

    /** Puts the clusters under a node next in the order, and notes where they are. */
    private static void place(Node n, List<Cluster> order) {
        n.underFirst = order.size();
        if (n.clusters != null) {
            n.first = order.size();
            order.addAll(n.clusters);
        } else {
            for (Node child : n.children) place(child, order);
        }
        n.underCount = order.size() - n.underFirst;
    }

    private static ClusterDag pack(Mesh mesh, List<Cluster> unordered) {
        // The tree first: it decides the clusters' order.
        java.util.TreeMap<Integer, List<Cluster>> byLevel = new java.util.TreeMap<>();
        for (Cluster c : unordered) byLevel.computeIfAbsent(c.level, k -> new ArrayList<>()).add(c);
        List<Node> nodes = new ArrayList<>();
        for (List<Cluster> clusters : byLevel.values()) nodes.add(node(clusters));
        int roots = nodes.size(), depths = 1;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (n.clusters != null) continue;
            n.first = nodes.size();
            for (Node child : n.children) {
                child.depth = n.depth + 1;
                depths = Math.max(depths, child.depth + 1);
            }
            nodes.addAll(n.children);
        }
        // The clusters in the order of a walk down each root, so the clusters under any node are side by side.
        List<Cluster> all = new ArrayList<>(unordered.size());
        for (int r = 0; r < roots; r++) place(nodes.get(r), all);
        int[] perDepth = new int[depths];
        for (Node n : nodes) perDepth[n.depth]++;
        int widest = 0;
        for (int d = 1; d < depths; d++) widest = Math.max(widest, perDepth[d]);
        int m = nodes.size();
        int[] nodeFirst = new int[m], nodeCount = new int[m], under = new int[m * 2];
        boolean[] leaf = new boolean[m];
        float[] nodeBounds = new float[m * 4], lodSphere = new float[m * 4], ownSphere = new float[m * 4], minError = new float[m], maxParentError = new float[m];
        for (int i = 0; i < m; i++) {
            Node n = nodes.get(i);
            nodeFirst[i] = n.first;
            leaf[i] = n.clusters != null;
            nodeCount[i] = leaf[i] ? n.clusters.size() : n.children.size();
            under[i * 2] = n.underFirst;
            under[i * 2 + 1] = n.underCount;
            System.arraycopy(n.bounds, 0, nodeBounds, i * 4, 4);
            System.arraycopy(n.lodSphere, 0, lodSphere, i * 4, 4);
            System.arraycopy(n.ownSphere, 0, ownSphere, i * 4, 4);
            minError[i] = n.minError;
            maxParentError[i] = n.maxParentError;
        }
        Tree tree = new Tree(nodeFirst, nodeCount, leaf, under, nodeBounds, lodSphere, ownSphere, minError, maxParentError, roots, depths, widest);

        int n = all.size(), total = 0;
        for (Cluster c : all) total += c.triangles.length;
        int[] indices = new int[total], first = new int[n], count = new int[n], level = new int[n];
        float[] error = new float[n], sphere = new float[n * 4], parentError = new float[n], parentSphere = new float[n * 4], bounds = new float[n * 4];
        int at = 0;
        for (int i = 0; i < n; i++) {
            Cluster c = all.get(i);
            first[i] = at;
            count[i] = c.triangles.length;
            System.arraycopy(c.triangles, 0, indices, at, c.triangles.length);
            at += c.triangles.length;
            level[i] = c.level;
            error[i] = c.error;
            System.arraycopy(c.sphere, 0, sphere, i * 4, 4);
            System.arraycopy(c.bounds, 0, bounds, i * 4, 4);
            parentError[i] = c.parentError;
            if (c.parentSphere != null) System.arraycopy(c.parentSphere, 0, parentSphere, i * 4, 4);
            else System.arraycopy(c.sphere, 0, parentSphere, i * 4, 4);
        }
        return new ClusterDag(mesh, indices, first, count, level, error, sphere, parentError, parentSphere, bounds, tree);
    }

    /**
     * The clusters to draw for a view, the reference the GPU's choice is checked against: those whose own error is small
     * enough on screen and whose replacement's is not.
     *
     * @param model         the node's transform, column-major
     * @param eye           the camera's position
     * @param pixelsPerUnit pixels one unit covers at distance 1 (half the picture's height over tan of half the field of view)
     * @param maxPixels     the error allowed on screen
     */
    int[] cut(float[] model, float[] eye, float pixelsPerUnit, float maxPixels) {
        float scale = (float) Math.sqrt(Math.max(model[0] * model[0] + model[1] * model[1] + model[2] * model[2],
                Math.max(model[4] * model[4] + model[5] * model[5] + model[6] * model[6], model[8] * model[8] + model[9] * model[9] + model[10] * model[10])));
        int[] chosen = new int[size()];
        int n = 0;
        for (int i = 0; i < size(); i++) {
            boolean fineEnough = projected(error[i], sphere, i, model, eye, scale, pixelsPerUnit) <= maxPixels;
            boolean parentTooCoarse = parentError[i] == Float.POSITIVE_INFINITY
                    || projected(parentError[i], parentSphere, i, model, eye, scale, pixelsPerUnit) > maxPixels;
            if (fineEnough && parentTooCoarse) chosen[n++] = i;
        }
        return Arrays.copyOf(chosen, n);
    }

    /**
     * {@link #cut}'s clusters found the way the GPU finds them, through the {@link #tree}, skipping the nodes it skips (all
     * but being out of view). {@code tested}, when given, gets the nodes and the clusters tested.
     */
    int[] walk(float[] model, float[] eye, float pixelsPerUnit, float maxPixels, long[] tested) {
        float scale = (float) Math.sqrt(Math.max(model[0] * model[0] + model[1] * model[1] + model[2] * model[2],
                Math.max(model[4] * model[4] + model[5] * model[5] + model[6] * model[6], model[8] * model[8] + model[9] * model[9] + model[10] * model[10])));
        int[] chosen = new int[size()];
        int n = 0;
        long nodes = 0, clusters = 0;
        int[] queue = new int[tree.size()];
        int head = 0, tail = 0;
        for (int r = 0; r < tree.roots(); r++) queue[tail++] = r;
        while (head < tail) {
            int node = queue[head++];
            nodes++;
            if (!needed(node, model, eye, scale, pixelsPerUnit, maxPixels)) continue;
            for (int k = 0; k < tree.count()[node]; k++) {
                int child = tree.first()[node] + k;
                if (!tree.leaf()[node]) {
                    queue[tail++] = child;
                    continue;
                }
                clusters++;
                boolean fineEnough = projected(error[child], sphere, child, model, eye, scale, pixelsPerUnit) <= maxPixels;
                boolean parentTooCoarse = parentError[child] == Float.POSITIVE_INFINITY
                        || projected(parentError[child], parentSphere, child, model, eye, scale, pixelsPerUnit) > maxPixels;
                if (fineEnough && parentTooCoarse) chosen[n++] = child;
            }
        }
        if (tested != null) {
            tested[0] = nodes;
            tested[1] = clusters;
        }
        int[] out = Arrays.copyOf(chosen, n);
        Arrays.sort(out);
        return out;
    }

    /** Whether a node of the {@link #tree} may hold a cluster to draw at this error: the test the GPU makes. */
    private boolean needed(int node, float[] m, float[] eye, float scale, float pixelsPerUnit, float maxPixels) {
        float replaced = tree.maxParentError()[node];
        if (replaced != Float.POSITIVE_INFINITY && projected(replaced, tree.lodSphere(), node, m, eye, scale, pixelsPerUnit) <= maxPixels * FINE_MARGIN) {
            return false;
        }
        float least = tree.minError()[node];
        if (least > 0f) {
            float[] s = tree.ownSphere();
            float x = s[node * 4], y = s[node * 4 + 1], z = s[node * 4 + 2];
            float wx = m[0] * x + m[4] * y + m[8] * z + m[12] - eye[0];
            float wy = m[1] * x + m[5] * y + m[9] * z + m[13] - eye[1];
            float wz = m[2] * x + m[6] * y + m[10] * z + m[14] - eye[2];
            float far = (float) Math.sqrt(wx * wx + wy * wy + wz * wz) + s[node * 4 + 3] * scale;
            if (least * scale * pixelsPerUnit / far > maxPixels * COARSE_MARGIN) return false;
        }
        return true;
    }

    /**
     * The errors, in the mesh's units, the mesh can be cut at with the same error everywhere, finest first: 0 for the mesh
     * itself, then for each level the largest error of a cluster at it or below. What rays hit, which can't change detail
     * along the mesh as the picture does.
     */
    synchronized float[] flatErrors() {
        if (flatErrors == null) {
            float[] e = new float[levels()];
            for (int i = 0; i < size(); i++) e[level[i]] = Math.max(e[level[i]], error[i]);
            for (int k = 1; k < e.length; k++) e[k] = Math.max(e[k], e[k - 1]);
            flatErrors = e;
            flats = new Mesh[e.length];
        }
        return flatErrors;
    }

    /**
     * The mesh cut at {@code flatErrors()[k]}: every cluster whose own error is within it and whose replacement's is not, so no
     * hole and no overlap, with only the vertices those clusters use. 0 is the mesh itself. Each cut is made once.
     */
    synchronized Mesh flat(int k) {
        float[] e = flatErrors();
        k = Math.max(0, Math.min(k, e.length - 1));
        if (k == 0) return mesh;
        if (flats[k] == null) {
            int total = 0;
            for (int i = 0; i < size(); i++) if (error[i] <= e[k] && parentError[i] > e[k]) total += count[i];
            int[] picked = new int[total];
            int at = 0;
            for (int i = 0; i < size(); i++) {
                if (error[i] <= e[k] && parentError[i] > e[k]) {
                    System.arraycopy(indices, first[i], picked, at, count[i]);
                    at += count[i];
                }
            }
            flats[k] = LodGenerator.compact(mesh, picked, false);
        }
        return flats[k];
    }

    /** The coarsest {@link #flat} cut made so far, 0 for none. */
    synchronized int coarsestFlatMade() {
        if (flats == null) return 0;
        for (int k = flats.length - 1; k > 0; k--) if (flats[k] != null) return k;
        return 0;
    }

    /** The coarsest {@link #flat} cut whose error is within {@code allowed}, in the mesh's units; 0 (the mesh) at the least. */
    int flatFor(float allowed) {
        float[] e = flatErrors();
        int k = 0;
        while (k + 1 < e.length && e[k + 1] <= allowed) k++;
        return k;
    }

    /** The error as pixels on screen, from the nearest point of the sphere (the full mesh inside the sphere). */
    private static float projected(float error, float[] spheres, int i, float[] m, float[] eye, float scale, float pixelsPerUnit) {
        if (error == 0f) return 0f;
        float x = spheres[i * 4], y = spheres[i * 4 + 1], z = spheres[i * 4 + 2], r = spheres[i * 4 + 3] * scale;
        float wx = m[0] * x + m[4] * y + m[8] * z + m[12] - eye[0];
        float wy = m[1] * x + m[5] * y + m[9] * z + m[13] - eye[1];
        float wz = m[2] * x + m[6] * y + m[10] * z + m[14] - eye[2];
        float d = (float) Math.sqrt(wx * wx + wy * wy + wz * wz) - r;
        if (d <= 1e-6f) return Float.POSITIVE_INFINITY;
        return error * scale * pixelsPerUnit / d;
    }
}
