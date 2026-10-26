package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a mesh that has dents in it (a chair, a bowl, an L-shaped corridor, a car with its wheel arches) into a few convex
 * pieces, so it can be a <em>dynamic</em> body. A {@link MeshShape} has no inside and stays where it is; a
 * {@link ConvexHullShape} is solid but fills in every dent. A body made of several hulls is both: it moves, and it
 * collides with static meshes, planes, and other bodies' hulls through the contact tests that already exist.
 * <pre>{@code
 * RigidBody chair = new RigidBody(node).setMass(8f);
 * for (ConvexHullShape piece : ConvexDecomposition.of(chairMesh, 12)) chair.addShape(piece);
 * }</pre>
 * It is an approximation: the mesh is cut by planes along the X, Y and Z axes, where the surface dents deepest inside the
 * hull around it, until no piece is dented by more than {@code tolerance} or {@code maxHulls} pieces are used. Each piece's
 * hull is the shrink-wrap of the corners (and the places the cuts made) of its part of the surface, so pieces cut from a solid
 * are solid, and a thin sheet comes out as thin slabs. Pieces may overlap a little along the cuts, which makes the body a
 * little heavier than its model: the mass is shared between the hulls by their volumes, then the body's mass is what you set.
 * More hulls follow the shape closer and cost more in every contact test (a body of twelve hulls is twelve shapes), so use
 * as few as look right; a low-detail version of the model makes a better source than the model itself.
 */
public final class ConvexDecomposition {
    private static final int SAMPLES_PER_TRIANGLE = 7;

    private ConvexDecomposition() {
    }

    /**
     * Cuts {@code mesh} into at most {@code maxHulls} hulls, in the mesh's own space, until no piece is dented by more than
     * a fiftieth of the mesh's size.
     */
    public static List<ConvexHullShape> of(Mesh mesh, int maxHulls) {
        return of(mesh, maxHulls, 0f);
    }

    /**
     * Cuts {@code mesh} into at most {@code maxHulls} hulls, until no piece's surface is more than {@code tolerance} (metres,
     * in the mesh's own space; 0 for a fiftieth of the mesh's size) inside the hull around it.
     */
    public static List<ConvexHullShape> of(Mesh mesh, int maxHulls, float tolerance) {
        if (mesh.isLines()) throw new IllegalArgumentException("A mesh of line segments has no surface to cut into hulls");
        float[] v = mesh.vertices();
        float[] positions = new float[mesh.vertexCount() * 3];
        for (int i = 0; i < mesh.vertexCount(); i++) System.arraycopy(v, i * Mesh.FLOATS_PER_VERTEX, positions, i * 3, 3);
        return of(positions, mesh.indices(), maxHulls, tolerance);
    }

    /**
     * Cuts the meshes of {@code node} and everything under it, in {@code node}'s own space (its own position, rotation and
     * scale are not applied, since the body on that node applies them), into at most {@code maxHulls} hulls.
     */
    public static List<ConvexHullShape> of(Node3D node, int maxHulls) {
        return of(node, maxHulls, 0f);
    }

    public static List<ConvexHullShape> of(Node3D node, int maxHulls, float tolerance) {
        List<float[]> parts = new ArrayList<>();
        List<int[]> partIndices = new ArrayList<>();
        MeshShape.collect(node, null, (positions, indices) -> {
            parts.add(positions);
            partIndices.add(indices);
        });
        if (parts.isEmpty()) throw new IllegalArgumentException(node + " has no meshes to cut into hulls");
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
        return of(positions, indices, maxHulls, tolerance);
    }

    /**
     * Cuts the triangles {@code indices} name in {@code positions} (x, y, z for each corner).
     *
     * @param maxHulls  the most pieces to make, at least 1
     * @param tolerance how far a piece's surface may be inside its hull, in the units of the positions; 0 or less for a
     *                  fiftieth of the mesh's size
     * @return the hulls, never empty
     * @throws IllegalArgumentException for no triangles, or when they have no area
     */
    public static List<ConvexHullShape> of(float[] positions, int[] indices, int maxHulls, float tolerance) {
        if (maxHulls < 1) throw new IllegalArgumentException("The most hulls must be at least 1, not " + maxHulls);
        if (indices.length % 3 != 0) throw new IllegalArgumentException("Triangles are three indices each");
        List<Triangle> triangles = new ArrayList<>(indices.length / 3);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < indices.length; i += 3) {
            Triangle t = new Triangle(corner(positions, indices[i]), corner(positions, indices[i + 1]), corner(positions, indices[i + 2]));
            if (t.area() <= 1e-12f) continue;
            triangles.add(t);
            for (Vec3 c : new Vec3[]{t.a, t.b, t.c}) {
                minX = Math.min(minX, c.x());
                minY = Math.min(minY, c.y());
                minZ = Math.min(minZ, c.z());
                maxX = Math.max(maxX, c.x());
                maxY = Math.max(maxY, c.y());
                maxZ = Math.max(maxZ, c.z());
            }
        }
        if (triangles.isEmpty()) throw new IllegalArgumentException("There are no triangles with an area to cut into hulls");
        float size = new Vec3(maxX - minX, maxY - minY, maxZ - minZ).length();
        float limit = tolerance > 0f ? tolerance : size * 0.02f;
        float thin = size * 0.004f;
        Part whole = Part.of(triangles, thin);
        if (whole == null) throw new IllegalArgumentException("The triangles don't make a shape");
        List<Part> parts = new ArrayList<>();
        parts.add(whole);
        while (parts.size() < maxHulls) {
            Part worst = null;
            for (Part part : parts) if (!part.uncuttable && part.dent > limit && (worst == null || part.dent > worst.dent)) worst = part;
            if (worst == null) break;
            Part[] halves = split(worst, thin);
            if (halves == null) {
                worst.uncuttable = true;
                continue;
            }
            parts.set(parts.indexOf(worst), halves[0]);
            parts.add(halves[1]);
        }
        List<ConvexHullShape> hulls = new ArrayList<>(parts.size());
        for (Part part : parts) hulls.add(part.hull);
        return hulls;
    }

    private static Vec3 corner(float[] positions, int index) {
        return new Vec3(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2]);
    }

    private record Triangle(Vec3 a, Vec3 b, Vec3 c) {
        float area() {
            return b.sub(a).cross(c.sub(a)).length() * 0.5f;
        }
    }

    /**
     * Some of the surface, its hull, and how far the surface dips inside it at the worst place.
     */
    private static final class Part {
        final List<Triangle> triangles;
        final ConvexHullShape hull;
        final float dent;
        final Vec3 deepest;
        boolean uncuttable;

        private Part(List<Triangle> triangles, ConvexHullShape hull, float dent, Vec3 deepest) {
            this.triangles = triangles;
            this.hull = hull;
            this.dent = dent;
            this.deepest = deepest;
        }

        /**
         * The part around these triangles, or null when they lie along a line or at a point. A flat part is given a
         * little thickness, so it has a hull to collide as.
         */
        static Part of(List<Triangle> triangles, float thin) {
            List<Vec3> points = new ArrayList<>(triangles.size() * 3);
            Triangle biggest = triangles.get(0);
            for (Triangle t : triangles) {
                points.add(t.a);
                points.add(t.b);
                points.add(t.c);
                if (t.area() > biggest.area()) biggest = t;
            }
            ConvexHullShape hull = null;
            try {
                hull = ConvexHullShape.of(points);
            } catch (IllegalArgumentException flat) {
                // all in one plane: below
            }
            Vec3 thinNormal = null;
            if (hull == null) {
                thinNormal = biggest.b.sub(biggest.a).cross(biggest.c.sub(biggest.a)).normalized();
            } else {
                Polytope polytope = hull.polytope();
                float narrowest = Float.POSITIVE_INFINITY;
                for (Vec3 normal : polytope.normals) {
                    float width = polytope.max(normal) - polytope.min(normal);
                    if (width < narrowest) {
                        narrowest = width;
                        thinNormal = normal;
                    }
                }
                if (narrowest >= thin) thinNormal = null;
            }
            if (thinNormal != null) { // give it a thickness of `thin` about the plane
                Vec3 push = thinNormal.mul(thin * 0.5f);
                List<Vec3> fat = new ArrayList<>(points.size() * 2);
                for (Vec3 p : points) {
                    fat.add(p.add(push));
                    fat.add(p.sub(push));
                }
                try {
                    hull = ConvexHullShape.of(fat);
                } catch (IllegalArgumentException line) {
                    return null;
                }
            }
            // How far inside the hull the surface gets: its corners, middles of its edges and its centres.
            Polytope polytope = hull.polytope();
            float dent = 0f;
            Vec3 deepest = triangles.get(0).a;
            for (Triangle t : triangles) {
                for (int s = 0; s < SAMPLES_PER_TRIANGLE; s++) {
                    Vec3 p = switch (s) {
                        case 0 -> t.a;
                        case 1 -> t.b;
                        case 2 -> t.c;
                        case 3 -> t.a.add(t.b).mul(0.5f);
                        case 4 -> t.b.add(t.c).mul(0.5f);
                        case 5 -> t.c.add(t.a).mul(0.5f);
                        default -> t.a.add(t.b).add(t.c).mul(1f / 3f);
                    };
                    float depth = Float.POSITIVE_INFINITY;
                    for (int f = 0; f < polytope.normals.length; f++) depth = Math.min(depth, polytope.offsets[f] - polytope.normals[f].dot(p));
                    if (depth > dent) {
                        dent = depth;
                        deepest = p;
                    }
                }
            }
            return new Part(triangles, hull, dent, deepest);
        }
    }

    /**
     * The best of the cuts along the three axes at the deepest dent and at the middle: the one after which the two
     * hulls hold the least volume. Null when none gives two parts.
     */
    private static Part[] split(Part part, float thin) {
        Bounds box = part.hull.bounds();
        Part[] best = null;
        float bestVolume = Float.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            float low = at(box.min(), axis), high = at(box.max(), axis);
            float margin = (high - low) * 0.02f;
            for (float cut : new float[]{at(part.deepest, axis), (low + high) * 0.5f}) {
                if (cut <= low + margin || cut >= high - margin) continue;
                List<Triangle> left = new ArrayList<>(), right = new ArrayList<>();
                for (Triangle t : part.triangles) clip(t, axis, cut, left, right);
                if (left.isEmpty() || right.isEmpty()) continue;
                Part l = Part.of(left, thin), r = Part.of(right, thin);
                if (l == null || r == null) continue;
                float volume = l.hull.volume() + r.hull.volume();
                if (volume < bestVolume) {
                    bestVolume = volume;
                    best = new Part[]{l, r};
                }
            }
        }
        return best;
    }

    private static float at(Vec3 v, int axis) {
        return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
    }

    /**
     * Puts the part of {@code t} below {@code cut} along {@code axis} in {@code left} and the rest in {@code right}, cutting
     * a triangle that crosses it into pieces.
     */
    private static void clip(Triangle t, int axis, float cut, List<Triangle> left, List<Triangle> right) {
        Vec3[] corners = {t.a, t.b, t.c};
        float[] d = {at(t.a, axis) - cut, at(t.b, axis) - cut, at(t.c, axis) - cut};
        if (d[0] <= 0f && d[1] <= 0f && d[2] <= 0f) {
            left.add(t);
            return;
        }
        if (d[0] >= 0f && d[1] >= 0f && d[2] >= 0f) {
            right.add(t);
            return;
        }
        List<Vec3> below = new ArrayList<>(4), above = new ArrayList<>(4);
        for (int i = 0; i < 3; i++) {
            Vec3 p = corners[i], q = corners[(i + 1) % 3];
            float dp = d[i], dq = d[(i + 1) % 3];
            if (dp <= 0f) below.add(p);
            if (dp >= 0f) above.add(p);
            if (dp < 0f && dq > 0f || dp > 0f && dq < 0f) {
                Vec3 x = p.add(q.sub(p).mul(dp / (dp - dq)));
                below.add(x);
                above.add(x);
            }
        }
        fan(below, left);
        fan(above, right);
    }

    private static void fan(List<Vec3> polygon, List<Triangle> into) {
        for (int i = 1; i + 1 < polygon.size(); i++) {
            Triangle piece = new Triangle(polygon.get(0), polygon.get(i), polygon.get(i + 1));
            if (piece.area() > 1e-12f) into.add(piece);
        }
    }
}
