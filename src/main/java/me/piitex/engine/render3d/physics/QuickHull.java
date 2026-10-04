package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the convex hull of a cloud of points by quickhull: start from a tetrahedron, then keep taking the point
 * farthest outside some face, cutting away the faces it can see and joining it to their outline. Faces that end up in
 * one plane (the two triangles of a box side) are merged into one polygon, which gives the narrowphase fewer faces to
 * test and whole faces to clip against.
 */
final class QuickHull {
    private static final class Face {
        final int a, b, c;
        final Vec3 normal;
        final float offset;
        final List<Integer> outside = new ArrayList<>();
        boolean dead;

        Face(Vec3[] p, int a, int b, int c) {
            this.a = a;
            this.b = b;
            this.c = c;
            normal = p[b].sub(p[a]).cross(p[c].sub(p[a])).normalized();
            offset = normal.dot(p[a]);
        }

        float distance(Vec3 point) {
            return normal.dot(point) - offset;
        }

        int corner(int i) {
            return i == 0 ? a : i == 1 ? b : c;
        }
    }

    private final Vec3[] points;
    private final float epsilon;
    private final List<Face> faces = new ArrayList<>();
    private final Map<Long, Face> byEdge = new HashMap<>(); // directed edge -> the face it belongs to

    private QuickHull(Vec3[] points) {
        this.points = points;
        float extent = 0f;
        for (Vec3 p : points) extent = Math.max(extent, Math.max(Math.abs(p.x()), Math.max(Math.abs(p.y()), Math.abs(p.z()))));
        epsilon = Math.max(extent, 1e-3f) * 1e-5f;
    }

    /**
     * The hull of {@code points}, which must have some volume.
     */
    static Polytope build(Vec3[] points) {
        Vec3[] unique = dedupe(points);
        if (unique.length < 4) throw new IllegalArgumentException("A convex hull needs at least 4 points that aren't all in one plane");
        QuickHull hull = new QuickHull(unique);
        hull.run();
        return hull.result();
    }

    private static Vec3[] dedupe(Vec3[] points) {
        Map<Vec3, Boolean> seen = new LinkedHashMap<>();
        for (Vec3 p : points) {
            if (!Float.isFinite(p.x()) || !Float.isFinite(p.y()) || !Float.isFinite(p.z())) throw new IllegalArgumentException("A hull point isn't finite: " + p);
            seen.putIfAbsent(new Vec3(p.x() + 0f, p.y() + 0f, p.z() + 0f), Boolean.TRUE); // + 0f folds -0 into 0
        }
        return seen.keySet().toArray(Vec3[]::new);
    }

    private void run() {
        int[] start = tetrahedron();
        int a = start[0], b = start[1], c = start[2], d = start[3];
        // Wind each face so it faces away from the fourth corner.
        if (new Face(points, a, b, c).distance(points[d]) > 0f) {
            int t = b;
            b = c;
            c = t;
        }
        addFace(a, b, c);
        addFace(a, d, b);
        addFace(b, d, c);
        addFace(c, d, a);
        for (int i = 0; i < points.length; i++) {
            if (i == a || i == b || i == c || i == d) continue;
            assign(i, faces);
        }
        while (true) {
            Face from = null;
            for (Face f : faces) {
                if (!f.dead && !f.outside.isEmpty()) {
                    from = f;
                    break;
                }
            }
            if (from == null) break;
            int eye = from.outside.getFirst();
            float most = from.distance(points[eye]);
            for (int i : from.outside) {
                float dist = from.distance(points[i]);
                if (dist > most) {
                    most = dist;
                    eye = i;
                }
            }
            addPoint(eye, from);
        }
    }

    /**
     * Four corners spanning a solid: the two points farthest apart along an axis, the one farthest from their line,
     * and the one farthest from the plane of the three.
     */
    private int[] tetrahedron() {
        int[] lo = new int[3], hi = new int[3];
        for (int i = 0; i < points.length; i++) {
            for (int k = 0; k < 3; k++) {
                if (Narrowphase.get(points[i], k) < Narrowphase.get(points[lo[k]], k)) lo[k] = i;
                if (Narrowphase.get(points[i], k) > Narrowphase.get(points[hi[k]], k)) hi[k] = i;
            }
        }
        int a = 0, b = 0;
        float widest = -1f;
        for (int k = 0; k < 3; k++) {
            float w = Narrowphase.get(points[hi[k]], k) - Narrowphase.get(points[lo[k]], k);
            if (w > widest) {
                widest = w;
                a = lo[k];
                b = hi[k];
            }
        }
        if (widest <= epsilon) throw flat();
        Vec3 line = points[b].sub(points[a]);
        int c = -1;
        float far = 0f;
        for (int i = 0; i < points.length; i++) {
            float dist = line.cross(points[i].sub(points[a])).length() / line.length();
            if (dist > far) {
                far = dist;
                c = i;
            }
        }
        if (far <= epsilon) throw flat();
        Vec3 n = line.cross(points[c].sub(points[a])).normalized();
        int d = -1;
        far = 0f;
        for (int i = 0; i < points.length; i++) {
            float dist = Math.abs(n.dot(points[i].sub(points[a])));
            if (dist > far) {
                far = dist;
                d = i;
            }
        }
        if (far <= epsilon) throw flat();
        return new int[]{a, b, c, d};
    }

    private static IllegalArgumentException flat() {
        return new IllegalArgumentException("The points are all in one plane or line, so they have no volume to make a convex hull from");
    }

    private Face addFace(int a, int b, int c) {
        Face f = new Face(points, a, b, c);
        faces.add(f);
        byEdge.put(key(a, b), f);
        byEdge.put(key(b, c), f);
        byEdge.put(key(c, a), f);
        return f;
    }

    private static long key(int from, int to) {
        return (long) from << 32 | (to & 0xffffffffL);
    }

    /**
     * Gives each point to the face it is farthest outside, or drops it when it is inside them all.
     */
    private boolean assign(int point, List<Face> candidates) {
        Face best = null;
        float most = epsilon;
        for (Face f : candidates) {
            if (f.dead) continue;
            float dist = f.distance(points[point]);
            if (dist > most) {
                most = dist;
                best = f;
            }
        }
        if (best != null) best.outside.add(point);
        return best != null;
    }

    private void addPoint(int eye, Face from) {
        Vec3 p = points[eye];
        // The faces the point sees, found by walking across edges from the first one so they stay one patch.
        List<Face> visible = new ArrayList<>();
        List<Face> stack = new ArrayList<>();
        stack.add(from);
        from.dead = true;
        while (!stack.isEmpty()) {
            Face f = stack.removeLast();
            visible.add(f);
            for (int i = 0; i < 3; i++) {
                Face neighbour = byEdge.get(key(f.corner((i + 1) % 3), f.corner(i)));
                if (neighbour != null && !neighbour.dead && neighbour.distance(p) > epsilon) {
                    neighbour.dead = true;
                    stack.add(neighbour);
                }
            }
        }
        // The outline: edges of the patch whose other side is a face that stays.
        List<int[]> horizon = new ArrayList<>();
        for (Face f : visible) {
            for (int i = 0; i < 3; i++) {
                int u = f.corner(i), v = f.corner((i + 1) % 3);
                Face other = byEdge.get(key(v, u));
                if (other != null && !other.dead) horizon.add(new int[]{u, v});
            }
        }
        for (Face f : visible) {
            for (int i = 0; i < 3; i++) {
                long k = key(f.corner(i), f.corner((i + 1) % 3));
                if (byEdge.get(k) == f) byEdge.remove(k);
            }
        }
        List<Face> created = new ArrayList<>(horizon.size());
        for (int[] edge : horizon) created.add(addFace(edge[0], edge[1], eye));
        for (Face f : visible) {
            // Usually a new face takes it; otherwise check the rest, so no point outside the hull is lost.
            for (int i : f.outside) if (i != eye && !assign(i, created)) assign(i, faces);
            f.outside.clear();
        }
        faces.removeIf(f -> f.dead);
    }

    /**
     * Merges faces lying in one plane into polygons and keeps only the corners in use.
     */
    private Polytope result() {
        int n = faces.size();
        int[] group = new int[n];
        java.util.Arrays.fill(group, -1);
        Map<Face, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(faces.get(i), i);
        List<List<Integer>> groups = new ArrayList<>();
        for (int seed = 0; seed < n; seed++) {
            if (group[seed] >= 0) continue;
            Face s = faces.get(seed);
            List<Integer> members = new ArrayList<>();
            group[seed] = groups.size();
            members.add(seed);
            for (int m = 0; m < members.size(); m++) {
                Face f = faces.get(members.get(m));
                for (int i = 0; i < 3; i++) {
                    Face neighbour = byEdge.get(key(f.corner((i + 1) % 3), f.corner(i)));
                    if (neighbour == null) continue;
                    int j = index.get(neighbour);
                    if (group[j] >= 0) continue;
                    // Compared with the seed, not the neighbour, so a gentle curve doesn't merge into one bent face.
                    boolean flat = neighbour.normal.dot(s.normal) > 1f - 1e-5f;
                    for (int k = 0; k < 3 && flat; k++) flat = Math.abs(s.distance(points[neighbour.corner(k)])) <= epsilon * 4f;
                    if (flat) {
                        group[j] = groups.size();
                        members.add(j);
                    }
                }
            }
            groups.add(members);
        }

        Map<Integer, Integer> remap = new LinkedHashMap<>();
        List<int[]> loops = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            // The polygon's outline: edges whose far side is in another group, chained end to start.
            Map<Integer, Integer> next = new HashMap<>();
            for (int m : groups.get(g)) {
                Face f = faces.get(m);
                for (int i = 0; i < 3; i++) {
                    int u = f.corner(i), v = f.corner((i + 1) % 3);
                    Face other = byEdge.get(key(v, u));
                    if (other == null || group[index.get(other)] != g) next.put(u, v);
                }
            }
            int first = next.keySet().iterator().next();
            List<Integer> loop = new ArrayList<>();
            int at = first;
            do {
                loop.add(at);
                at = next.get(at);
            } while (at != first && loop.size() <= next.size());
            int[] l = new int[loop.size()];
            for (int i = 0; i < l.length; i++) l[i] = remap.computeIfAbsent(loop.get(i), _ -> remap.size());
            loops.add(l);
        }
        Vec3[] corners = new Vec3[remap.size()];
        for (Map.Entry<Integer, Integer> e : remap.entrySet()) corners[e.getValue()] = points[e.getKey()];
        return Polytope.of(corners, loops.toArray(int[][]::new));
    }
}
