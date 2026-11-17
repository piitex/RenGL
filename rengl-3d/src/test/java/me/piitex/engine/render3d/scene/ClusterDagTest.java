package me.piitex.engine.render3d.scene;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cluster graph of virtual geometry and the CPU's choice of clusters, which the GPU's is checked against: errors and
 * spheres that only grow going up, and a choice with no crack or overlap at any distance, with parts at different levels.
 */
class ClusterDagTest {
    /** A lumpy ball, about 8000 triangles, with its seams welded so it is closed. */
    private static Mesh ball() {
        Mesh sphere = Mesh.sphere(1f, 96, 48);
        float[] v = sphere.vertices();
        int n = sphere.vertexCount();
        Map<String, Integer> ids = new HashMap<>();
        int[] weld = new int[n];
        float[] positions = new float[n * 3];
        int unique = 0;
        for (int i = 0; i < n; i++) {
            float x = v[i * 8], y = v[i * 8 + 1], z = v[i * 8 + 2];
            String key = Math.round(x * 1e5) + "," + Math.round(y * 1e5) + "," + Math.round(z * 1e5);
            Integer id = ids.get(key);
            if (id == null) {
                id = unique++;
                ids.put(key, id);
                float bump = 1f + 0.12f * (float) (Math.sin(x * 5) * Math.sin(y * 4) + Math.sin(z * 6));
                positions[id * 3] = x * bump;
                positions[id * 3 + 1] = y * bump;
                positions[id * 3 + 2] = z * bump;
            }
            weld[i] = id;
        }
        int[] source = sphere.indices();
        int[] indices = new int[source.length];
        int count = 0;
        for (int t = 0; t < source.length; t += 3) {
            int a = weld[source[t]], b = weld[source[t + 1]], c = weld[source[t + 2]];
            if (a == b || b == c || a == c) continue; // the poles' slivers
            indices[count++] = a;
            indices[count++] = b;
            indices[count++] = c;
        }
        return new Mesh(java.util.Arrays.copyOf(positions, unique * 3), null, null, java.util.Arrays.copyOf(indices, count));
    }

    private static final Mesh BALL = ball();
    private static final ClusterDag DAG = ClusterDag.build(BALL);

    @Test
    void manyLevelsFromFineToCoarse() {
        assertTrue(DAG.levels() >= 4, "levels: " + DAG.levels());
        int finest = 0, finestTriangles = 0, tops = 0;
        for (int i = 0; i < DAG.size(); i++) {
            if (DAG.level[i] == 0) {
                finest++;
                finestTriangles += DAG.count[i];
            }
            if (DAG.parentError[i] == Float.POSITIVE_INFINITY) tops++;
            assertTrue(DAG.count[i] <= ClusterDag.MAX_TRIANGLES * 3 && DAG.count[i] >= 3);
        }
        assertEquals(BALL.indexCount(), finestTriangles, "the finest level is the whole mesh");
        assertTrue(finest >= BALL.indexCount() / 3 / ClusterDag.MAX_TRIANGLES, finest + " finest clusters");
        assertTrue(tops < finest / 8, tops + " clusters at the top of the graph");
    }

    @Test
    void errorsAndSpheresOnlyGrowGoingUp() {
        for (int i = 0; i < DAG.size(); i++) {
            if (DAG.parentError[i] == Float.POSITIVE_INFINITY) continue;
            assertTrue(DAG.parentError[i] >= DAG.error[i], "cluster " + i + ": " + DAG.error[i] + " then " + DAG.parentError[i]);
            float dx = DAG.parentSphere[i * 4] - DAG.sphere[i * 4], dy = DAG.parentSphere[i * 4 + 1] - DAG.sphere[i * 4 + 1],
                    dz = DAG.parentSphere[i * 4 + 2] - DAG.sphere[i * 4 + 2];
            float apart = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertTrue(apart + DAG.sphere[i * 4 + 3] <= DAG.parentSphere[i * 4 + 3] * 1.0001f + 1e-5f, "cluster " + i + "'s sphere is inside its parent's");
        }
    }

    @Test
    void theChoiceHasNoCrackAtAnyDistance() {
        float pixelsPerUnit = 540f / (float) Math.tan(Math.toRadians(30));
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        int before = Integer.MAX_VALUE;
        // From inside the surface's reach out to far off: near ones mix levels, the near side fine and the far side coarse.
        for (float distance : new float[]{1.2f, 1.6f, 2.5f, 4f, 8f, 20f, 60f, 200f, 1000f, 10000f}) {
            int[] chosen = DAG.cut(identity, new float[]{0, 0.3f, distance}, pixelsPerUnit, 1f);
            int triangles = 0;
            Map<Long, Integer> edges = new HashMap<>();
            java.util.Set<Integer> levels = new java.util.TreeSet<>();
            for (int c : chosen) {
                levels.add(DAG.level[c]);
                for (int k = DAG.first[c]; k < DAG.first[c] + DAG.count[c]; k += 3) {
                    triangles++;
                    for (int e = 0; e < 3; e++) {
                        int a = DAG.indices[k + e], b = DAG.indices[k + (e + 1) % 3];
                        long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
                        edges.merge(key, 1, Integer::sum);
                    }
                }
            }
            int open = 0, over = 0;
            for (int uses : edges.values()) {
                if (uses == 1) open++;
                if (uses > 2) over++;
            }
            assertTrue(chosen.length > 0, "something is drawn at " + distance);
            assertEquals(0, open, "at " + distance + ": " + open + " edges with a hole beside them, levels " + levels);
            assertEquals(0, over, "at " + distance + ": " + over + " edges with overlapping triangles, levels " + levels);
            assertTrue(triangles <= before, "farther draws no more triangles: " + triangles + " at " + distance);
            before = triangles;
        }
        int full = 0;
        for (int c : DAG.cut(identity, new float[]{0, 0.3f, 5f}, pixelsPerUnit, 1e-9f)) full += DAG.count[c] / 3;
        assertEquals(BALL.indexCount() / 3, full, "with next to no error allowed, the full mesh");
        assertTrue(before < BALL.indexCount() / 3 / 20, "far off, a small part of the triangles: " + before);
    }

    /** What rays hit: the mesh cut with one error everywhere, closed at every error, simpler as it grows. */
    @Test
    void evenCutsAreClosedAndGetSimpler() {
        float[] errors = DAG.flatErrors();
        assertEquals(DAG.levels(), errors.length);
        assertEquals(0f, errors[0]);
        assertSame(BALL, DAG.flat(0), "the finest is the mesh itself");
        int before = Integer.MAX_VALUE;
        for (int k = 0; k < errors.length; k++) {
            if (k > 0) assertTrue(errors[k] >= errors[k - 1], "errors only grow");
            Mesh cut = DAG.flat(k);
            assertSame(cut, DAG.flat(k), "each cut is made once");
            Map<Long, Integer> edges = new HashMap<>();
            int[] idx = cut.indices();
            for (int t = 0; t < idx.length; t += 3) {
                for (int e = 0; e < 3; e++) {
                    int a = idx[t + e], b = idx[t + (e + 1) % 3];
                    edges.merge((long) Math.min(a, b) << 32 | Math.max(a, b), 1, Integer::sum);
                }
            }
            long bad = edges.values().stream().filter(u -> u != 2).count();
            assertEquals(0, bad, "cut " + k + " (error " + errors[k] + "): " + bad + " edges not shared by two triangles");
            assertTrue(idx.length / 3 <= before, "cut " + k + " has no more triangles than the one before");
            before = idx.length / 3;
            int picked = DAG.flatFor(errors[k]);
            assertTrue(picked >= k && errors[picked] == errors[k], "the error of cut " + k + " picks it, or a coarser one with the same error: " + picked);
        }
        assertTrue(before < BALL.indexCount() / 3 / 4, "the coarsest is far simpler: " + before + " triangles");
        assertEquals(0, DAG.flatFor(0f));
        assertEquals(errors.length - 1, DAG.flatFor(Float.MAX_VALUE));
    }

    private static boolean holds(float[] outer, int o, float[] inner, int i) {
        float dx = outer[o] - inner[i], dy = outer[o + 1] - inner[i + 1], dz = outer[o + 2] - inner[i + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + inner[i + 3] <= outer[o + 3];
    }

    /** Every cluster sits in one leaf; every node's spheres hold what is under them, and its errors bound theirs. */
    @Test
    void theTreeHoldsEveryClusterOnce() {
        ClusterDag.Tree tree = DAG.tree;
        int[] seen = new int[DAG.size()];
        int[] parentOf = new int[tree.size()];
        java.util.Arrays.fill(parentOf, -1);
        assertTrue(tree.roots() == DAG.levels(), "a root per level");
        for (int node = 0; node < tree.size(); node++) {
            int f = tree.first()[node], c = tree.count()[node];
            assertTrue(c >= 1 && c <= (tree.leaf()[node] ? ClusterDag.LEAF_CLUSTERS : ClusterDag.FAN_OUT), "node " + node + " has " + c);
            for (int k = f; k < f + c; k++) {
                if (tree.leaf()[node]) {
                    seen[k]++;
                    assertTrue(holds(tree.bounds(), node * 4, DAG.bounds, k * 4), "leaf " + node + " holds cluster " + k + "'s triangles");
                    assertTrue(holds(tree.lodSphere(), node * 4, DAG.parentSphere, k * 4), "and its replacement");
                    assertTrue(holds(tree.ownSphere(), node * 4, DAG.sphere, k * 4), "and its sphere");
                    assertTrue(tree.minError()[node] <= DAG.error[k] && tree.maxParentError()[node] >= DAG.parentError[k]);
                } else {
                    assertTrue(k > node && parentOf[k] == -1, "node " + k + " has one parent, before it");
                    parentOf[k] = node;
                    for (float[] s : new float[][]{tree.bounds(), tree.lodSphere(), tree.ownSphere()}) assertTrue(holds(s, node * 4, s, k * 4));
                    assertTrue(tree.minError()[node] <= tree.minError()[k] && tree.maxParentError()[node] >= tree.maxParentError()[k]);
                }
            }
        }
        for (int i = 0; i < DAG.size(); i++) assertEquals(1, seen[i], "cluster " + i + " is in one leaf");
        // The clusters under each node are side by side: a leaf's own, or its children's one after the other.
        int[] under = tree.under();
        int next = 0;
        for (int r = 0; r < tree.roots(); r++) {
            assertEquals(next, under[r * 2], "root " + r + " starts where the one before ends");
            next += under[r * 2 + 1];
        }
        assertEquals(DAG.size(), next, "the roots hold every cluster");
        for (int node = 0; node < tree.size(); node++) {
            int f = tree.first()[node], c = tree.count()[node];
            if (tree.leaf()[node]) {
                assertTrue(under[node * 2] == f && under[node * 2 + 1] == c, "leaf " + node + " holds its own clusters");
                continue;
            }
            int at = under[node * 2];
            for (int k = f; k < f + c; k++) {
                assertEquals(at, under[k * 2], "node " + k + " follows its sibling");
                at += under[k * 2 + 1];
            }
            assertEquals(under[node * 2] + under[node * 2 + 1], at, "node " + node + "'s children hold all under it");
        }
        for (int node = tree.roots(); node < tree.size(); node++) assertTrue(parentOf[node] >= 0, "node " + node + " is under a root");
        assertTrue(tree.depths() >= 2 && tree.widest() > 0, tree.depths() + " deep");
    }

    /**
     * Walked as the GPU walks it, the tree finds exactly the clusters {@link ClusterDag#cut} picks, from close (levels mixed)
     * to far off, moved, scaled and with other errors allowed, while testing only a part of the graph.
     */
    @Test
    void theWalkFindsTheCutAndSkipsMostOfTheGraph() {
        ClusterDag fine = ClusterDag.build(ClusterProbe.ball(1f, 256, 128));
        float pixelsPerUnit = 540f / (float) Math.tan(Math.toRadians(30));
        java.util.Random random = new java.util.Random(7);
        StringBuilder report = new StringBuilder();
        for (ClusterDag dag : new ClusterDag[]{DAG, fine}) {
            long tested = 0, all = 0;
            for (int i = 0; i < 400; i++) {
                float scale = 0.2f + random.nextFloat() * 8f;
                float[] m = {scale, 0, 0, 0, 0, scale, 0, 0, 0, 0, scale, 0, random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2, 0, 1};
                float distance = scale * (1.05f + (float) Math.pow(10, random.nextFloat() * 4));
                double a = random.nextDouble() * Math.PI * 2, b = random.nextDouble() * Math.PI - Math.PI / 2;
                float[] eye = {(float) (Math.cos(a) * Math.cos(b) * distance), (float) (Math.sin(b) * distance), (float) (Math.sin(a) * Math.cos(b) * distance)};
                float maxPixels = new float[]{0.25f, 1f, 2f, 8f}[i % 4];
                long[] counts = new long[2];
                int[] walked = dag.walk(m, eye, pixelsPerUnit, maxPixels, counts);
                assertArrayEquals(dag.cut(m, eye, pixelsPerUnit, maxPixels), walked, "view " + i + " at " + distance);
                tested += counts[0] + counts[1];
                all += dag.size();
            }
            report.append(String.format(" %d clusters: %.1f%% tested;", dag.size(), 100.0 * tested / all));
            assertTrue(tested < all / 2, "the walk tests less than half the graph:" + report);
        }
        System.out.println("[hierarchy]" + report);
    }

    @Test
    void aMixedCutReallyMixesLevels() {
        float pixelsPerUnit = 540f / (float) Math.tan(Math.toRadians(30));
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        // Close to one side of a big ball: the near side needs the finest level, the far side much less.
        float[] big = identity.clone();
        big[0] = big[5] = big[10] = 6f;
        int[] chosen = DAG.cut(big, new float[]{0, 0, 9f}, pixelsPerUnit, 1f);
        java.util.Set<Integer> levels = new java.util.TreeSet<>();
        for (int c : chosen) levels.add(DAG.level[c]);
        assertTrue(levels.size() >= 2, "levels in one view: " + levels);
    }
}
