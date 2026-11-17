package me.piitex.engine.render3d.scene;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the GPU tests of virtual geometry (in another package) need from the CPU side: a closed, lumpy test mesh, and the
 * reference choice of clusters ({@link ClusterDag#cut}) they check the GPU's against.
 */
public final class ClusterProbe {
    private ClusterProbe() {
    }

    /** A lumpy ball of about 9000 triangles, with its seams welded so it is closed. */
    public static Mesh ball(float radius) {
        return ball(radius, 96, 48);
    }

    /** The same ball with more or fewer triangles: about {@code 2 * segments * rings}. */
    public static Mesh ball(float radius, int segments, int rings) {
        Mesh sphere = Mesh.sphere(radius, segments, rings);
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
                float ux = x / radius, uy = y / radius, uz = z / radius;
                float bump = 1f + 0.12f * (float) (Math.sin(ux * 5) * Math.sin(uy * 4) + Math.sin(uz * 6));
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
            if (a == b || b == c || a == c) continue;
            indices[count++] = a;
            indices[count++] = b;
            indices[count++] = c;
        }
        return new Mesh(Arrays.copyOf(positions, unique * 3), null, null, Arrays.copyOf(indices, count));
    }

    /** The triangles of the clusters the CPU picks for a placement of the mesh (which must have clusters). */
    public static long cutTriangles(Mesh mesh, float[] model, float[] eye, float pixelsPerUnit, float maxPixels) {
        ClusterDag dag = mesh.clusters();
        long triangles = 0;
        for (int c : dag.cut(model, eye, pixelsPerUnit, maxPixels)) triangles += dag.count[c] / 3;
        return triangles;
    }

    /** The coarsest even cut of the mesh's clusters made for rays so far, 0 for none; and how many even cuts it has. */
    public static int coarsestRayCut(Mesh mesh) {
        return mesh.clusters().coarsestFlatMade();
    }

    public static int rayCuts(Mesh mesh) {
        return mesh.clusters().flatErrors().length;
    }

    /** Makes the GPU always walk the hierarchy over the clusters, or never (test every cluster of every record). */
    public static void walkHierarchy(boolean on) {
        VirtualGeometry.walkFrom = on ? 0 : Long.MAX_VALUE;
    }

    /** Back to walking it where a job has enough cluster tests. */
    public static void walkHierarchyAsUsual() {
        VirtualGeometry.walkFrom = VirtualGeometry.WALK_FROM;
    }

    /**
     * Nanoseconds the GPU takes, on average over {@code repeats}, to pick the clusters of one frame for a mesh placed at each
     * of {@code models} (column-major, every one in view), for the camera at {@code maxPixels} and the shadows at
     * {@code shadowPixels}: walking the hierarchy or testing every cluster. {@code finish} must wait until the GPU is done.
     */
    public static long timeCut(me.piitex.engine.render3d.GpuDevice device, Runnable finish, Mesh mesh, float[][] models, float[] eye,
                               float pixelsPerUnit, float maxPixels, float shadowPixels, boolean walk, int repeats) {
        long was = VirtualGeometry.walkFrom;
        walkHierarchy(walk);
        java.nio.ByteBuffer data = org.lwjgl.system.MemoryUtil.memCalloc(models.length * 36 * 4).order(java.nio.ByteOrder.nativeOrder());
        me.piitex.engine.render3d.GpuBuffer records = null;
        try (VirtualGeometry vg = new VirtualGeometry(device); VirtualGeometry.Frame frame = new VirtualGeometry.Frame()) {
            for (int i = 0; i < models.length; i++) {
                for (int k = 0; k < 16; k++) data.putFloat((i * 36 + k) * 4, models[i][k]);
            }
            records = device.createBuffer(me.piitex.engine.render3d.BufferType.STORAGE, data);
            float[] planes = new float[24];
            for (int p = 0; p < 6; p++) planes[p * 4 + 3] = 1f; // inside everywhere
            me.piitex.engine.render3d.math.Vec3 at = new me.piitex.engine.render3d.math.Vec3(eye[0], eye[1], eye[2]);
            java.util.List<VirtualGeometry.Job> jobs = java.util.List.of(new VirtualGeometry.Job(mesh.clusters(), 0, models.length,
                    models.length, maxPixels, shadowPixels, new VirtualGeometry.Lists()));
            for (int i = 0; i < 3; i++) vg.cut(frame, jobs, planes, at, pixelsPerUnit, records, 0, false);
            finish.run();
            long start = System.nanoTime();
            for (int i = 0; i < repeats; i++) vg.cut(frame, jobs, planes, at, pixelsPerUnit, records, 0, false);
            finish.run();
            return (System.nanoTime() - start) / repeats;
        } finally {
            if (records != null) records.close();
            org.lwjgl.system.MemoryUtil.memFree(data);
            VirtualGeometry.walkFrom = was;
        }
    }

    /** How many times a job has walked the hierarchy so far. */
    public static long walks() {
        return VirtualGeometry.walks;
    }

    /** The levels the CPU's pick mixes for that placement. */
    public static Set<Integer> cutLevels(Mesh mesh, float[] model, float[] eye, float pixelsPerUnit, float maxPixels) {
        ClusterDag dag = mesh.clusters();
        Set<Integer> levels = new TreeSet<>();
        for (int c : dag.cut(model, eye, pixelsPerUnit, maxPixels)) levels.add(dag.level[c]);
        return levels;
    }
}
