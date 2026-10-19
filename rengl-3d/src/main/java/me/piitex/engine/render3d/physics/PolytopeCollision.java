package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * Contact tests for polytopes (hulls, boxes against hulls, mesh triangles), filling a {@link Manifold} the way
 * {@link Narrowphase} does: the normal from the first shape to the second, and the points where they touch.
 */
final class PolytopeCollision {
    private static final float EPSILON = 1e-6f;
    /**
     * How much better, in metres, an axis has to be before it replaces one that gives steadier contacts.
     */
    private static final float BIAS = 1e-3f;

    private PolytopeCollision() {
    }

    /**
     * A mesh triangle being tested, with which of its edges are real. A contact the narrowphase finds at a seam (where
     * the triangle meets a flat or inward-turned neighbour) is turned to face straight out of the triangle, so things
     * slide over seams instead of catching on them.
     */
    record MeshTriangle(Vec3 normal, Vec3 corner, int realEdges) {
        /**
         * {@code n} points from the other shape toward the triangle; {@code corners} has a bit for each of the
         * triangle's corners the contact is at (one for a corner, two for an edge, three for the face).
         * {@code middle} is the other shape's middle, which says which side of the triangle it is on: {@code n} can't,
         * when it runs along the triangle (a box's side against the triangle's edge).
         */
        Vec3 fix(Vec3 n, int corners, Vec3 middle) {
            float side = normal.dot(middle.sub(corner));
            if (Math.abs(side) < 1e-5f) side = -normal.dot(n);
            Vec3 face = side >= 0f ? normal : normal.negate(); // the side facing the other shape
            if (face.dot(n) < -0.9999f) return n;
            boolean seam = switch (Integer.bitCount(corners & 7)) {
                case 3 -> true;
                case 2 -> {
                    int edge = (corners & 3) == 3 ? 0 : (corners & 6) == 6 ? 1 : 2;
                    yield (realEdges & 1 << edge) == 0;
                }
                case 1 -> {
                    int v = Integer.numberOfTrailingZeros(corners);
                    yield (realEdges & (1 << v | 1 << (v + 2) % 3)) == 0;
                }
                default -> false;
            };
            return seam ? face.negate() : n;
        }
    }

    // ---- Polytope against polytope ---------------------------------------------------------------------------------

    /**
     * Two polytopes by the separating axis test: the face normals of each and the crossings of their edge directions.
     * A face contact clips the other polytope's face that turns most against it to the touching face; an edge contact is
     * one point between the two edges. {@code triangle} is set when {@code b} is a mesh triangle.
     */
    static void polytopes(Polytope a, Polytope b, float margin, Manifold out, MeshTriangle triangle) {
        float faceA = Float.NEGATIVE_INFINITY, faceB = Float.NEGATIVE_INFINITY;
        int fa = 0, fb = 0;
        for (int f = 0; f < a.normals.length; f++) {
            float s = b.min(a.normals[f]) - a.offsets[f];
            if (s > margin) return;
            if (s > faceA) {
                faceA = s;
                fa = f;
            }
        }
        for (int f = 0; f < b.normals.length; f++) {
            float s = a.min(b.normals[f]) - b.offsets[f];
            if (s > margin) return;
            if (s > faceB) {
                faceB = s;
                fb = f;
            }
        }
        EdgeAxis best = new EdgeAxis();
        if (!edgeAxes(a, b, margin, best)) return;
        float edge = best.separation;
        Vec3 edgeNormal = best.normal;
        int da = best.directionA, db = best.directionB;

        // Faces give steadier contacts, so an edge or the second polytope's face has to be clearly better to win.
        boolean useB = clearlyBetter(faceB, faceA, BIAS);
        float bestFace = useB ? faceB : faceA;
        boolean useEdge = edgeNormal != null && clearlyBetter(edge, bestFace, 2f * BIAS);
        Vec3 n = useEdge ? edgeNormal : useB ? b.normals[fb].negate() : a.normals[fa];
        if (triangle != null) {
            Vec3 fixed = triangle.fix(n, lowestCorners(b, n), middle(a));
            if (fixed != n) {
                useEdge = false;
                useB = true;
                fb = b.faceToward(fixed.negate());
            }
        }
        if (useEdge && edgeContact(a, da, b, db, edgeNormal, edge, out)) return;
        if (useB) {
            clip(b, fb, a, margin, out);
            out.normal = b.normals[fb].negate();
        } else {
            clip(a, fa, b, margin, out);
            out.normal = a.normals[fa];
        }
    }

    /**
     * With at least this many pairs of edges, only the pairs that can be an edge of the gap between the polytopes are
     * tried (the Gauss map test below), not every pair. Two hulls of a few hundred corners have hundreds of thousands of
     * pairs and a handful hundred that matter.
     */
    static int prunePairs = 600;

    /**
     * The edge axis with the widest gap found by {@link #edgeAxes}.
     */
    private static final class EdgeAxis {
        float separation = Float.NEGATIVE_INFINITY;
        Vec3 normal;
        int directionA = -1, directionB = -1;

        void consider(Polytope a, Polytope b, Vec3 axis, int da, int db, float margin, boolean[] apart) {
            float aMin = a.min(axis), aMax = a.max(axis), bMin = b.min(axis), bMax = b.max(axis);
            float ahead = bMin - aMax, behind = aMin - bMax;
            float s = Math.max(ahead, behind);
            if (s > margin) {
                apart[0] = true;
                return;
            }
            if (s > separation) {
                separation = s;
                normal = ahead >= behind ? axis : axis.negate();
                directionA = da;
                directionB = db;
            }
        }
    }

    /**
     * Finds the crossing of an edge of each polytope that holds them furthest apart (or least overlapping).
     *
     * @return false if one of those axes separates them by more than the margin: no contact
     */
    private static boolean edgeAxes(Polytope a, Polytope b, float margin, EdgeAxis best) {
        boolean[] apart = {false};
        int edgesA = a.edges.length / 2, edgesB = b.edges.length / 2;
        if (a.edgeFaces == null || b.edgeFaces == null || edgesA * edgesB < prunePairs) {
            for (int i = 0; i < a.directions.length; i++) {
                for (int j = 0; j < b.directions.length; j++) {
                    Vec3 axis = a.directions[i].cross(b.directions[j]);
                    float length = axis.length();
                    if (length < 1e-3f) continue; // parallel edges: the face axes already cover this
                    best.consider(a, b, axis.mul(1f / length), i, j, margin, apart);
                    if (apart[0]) return false;
                }
            }
            return true;
        }
        for (int i = 0; i < edgesA; i++) {
            Vec3 a1 = a.normals[a.edgeFaces[i * 2]], a2 = a.normals[a.edgeFaces[i * 2 + 1]];
            Vec3 aCross = a2.cross(a1);
            Vec3 dirA = a.vertices[a.edges[i * 2 + 1]].sub(a.vertices[a.edges[i * 2]]);
            for (int j = 0; j < edgesB; j++) {
                // Seen from B, the faces turn the other way round.
                Vec3 c = b.normals[b.edgeFaces[j * 2]].negate(), d = b.normals[b.edgeFaces[j * 2 + 1]].negate();
                if (!minkowskiFace(a1, a2, aCross, c, d, d.cross(c))) continue;
                Vec3 axis = dirA.cross(b.vertices[b.edges[j * 2 + 1]].sub(b.vertices[b.edges[j * 2]]));
                float length = axis.length();
                if (length < 1e-3f * dirA.length() * b.vertices[b.edges[j * 2 + 1]].sub(b.vertices[b.edges[j * 2]]).length()) continue;
                best.consider(a, b, axis.mul(1f / length), a.edgeDirection[i], b.edgeDirection[j], margin, apart);
                if (apart[0]) return false;
            }
        }
        return true;
    }

    /**
     * Whether the arcs a-b and c-d, between unit normals on the sphere, cross: then the two edges whose faces they are
     * make a face of the shape of all differences between the polytopes' points, and only those edge pairs can give
     * the axis that separates two polytopes (Gregorius, 2013). {@code bxa} and {@code dxc} are the planes the arcs lie
     * in. The tests are loosened a hair, since an arc just touching the other is as good as crossing.
     */
    private static boolean minkowskiFace(Vec3 a, Vec3 b, Vec3 bxa, Vec3 c, Vec3 d, Vec3 dxc) {
        float cba = c.dot(bxa), dba = d.dot(bxa), adc = a.dot(dxc), bdc = b.dot(dxc);
        final float tolerance = 1e-7f;
        return cba * dba < tolerance && adc * bdc < tolerance && cba * bdc > -tolerance;
    }

    private static Vec3 middle(Polytope p) {
        Vec3 sum = Vec3.ZERO;
        for (Vec3 v : p.vertices) sum = sum.add(v);
        return sum.mul(1f / p.vertices.length);
    }

    private static boolean clearlyBetter(float candidate, float current, float bias) {
        return candidate > current + 0.05f * Math.abs(current) + bias;
    }

    /**
     * The corners of {@code p} nearest the start of {@code n} (its side facing the other shape), as bits.
     */
    private static int lowestCorners(Polytope p, Vec3 n) {
        float lowest = p.min(n);
        int corners = 0;
        for (int i = 0; i < p.vertices.length && i < 32; i++) if (p.vertices[i].dot(n) <= lowest + 1e-4f) corners |= 1 << i;
        return corners;
    }

    /**
     * Clips the incident polytope's face that turns most against the reference face to the reference face's sides,
     * keeping the corners within the margin of it. The reference face's normal points toward the incident polytope.
     */
    private static void clip(Polytope ref, int face, Polytope inc, float margin, Manifold out) {
        Vec3 rn = ref.normals[face];
        int[] loop = inc.faces[inc.faceToward(rn.negate())];
        Vec3[] polygon = new Vec3[Math.max(loop.length * 2, 8)];
        int size = loop.length;
        for (int i = 0; i < size; i++) polygon[i] = inc.vertices[loop[i]];

        int[] refLoop = ref.faces[face];
        Vec3 middle = Vec3.ZERO;
        for (int index : refLoop) middle = middle.add(ref.vertices[index]);
        middle = middle.mul(1f / refLoop.length);
        for (int k = 0; k < refLoop.length && size > 0; k++) {
            Vec3 p = ref.vertices[refLoop[k]], q = ref.vertices[refLoop[(k + 1) % refLoop.length]];
            Vec3 side = q.sub(p).cross(rn);
            if (side.dot(middle.sub(p)) > 0f) side = side.negate(); // pointing out of the face, whatever the winding
            float offset = side.dot(p);
            Vec3[] clipped = new Vec3[Math.max(size * 2, 8)];
            int count = 0;
            for (int i = 0; i < size; i++) {
                Vec3 x = polygon[i], y = polygon[(i + 1) % size];
                float dx = side.dot(x) - offset, dy = side.dot(y) - offset;
                if (dx <= 0f) clipped[count++] = x;
                if ((dx < 0f && dy > 0f) || (dx > 0f && dy < 0f)) clipped[count++] = x.lerp(y, dx / (dx - dy));
            }
            polygon = clipped;
            size = count;
        }
        for (int i = 0; i < size; i++) {
            Vec3 p = polygon[i];
            float separation = p.dot(rn) - ref.offsets[face];
            if (separation <= margin) out.add(p.sub(rn.mul(separation * 0.5f)), -separation);
        }
    }

    /**
     * One point midway between the two edges that touch along {@code n}. False when the edges can't be told apart, so
     * the caller falls back to faces.
     */
    private static boolean edgeContact(Polytope a, int da, Polytope b, int db, Vec3 n, float separation, Manifold out) {
        float top = a.max(n), bottom = b.min(n);
        int ea = supportEdge(a, da, n, top, 1f), eb = supportEdge(b, db, n, bottom, -1f);
        if (ea < 0 || eb < 0) return false;
        Vec3[] closest = Narrowphase.closestBetweenSegments(a.vertices[a.edges[ea * 2]], a.vertices[a.edges[ea * 2 + 1]],
                b.vertices[b.edges[eb * 2]], b.vertices[b.edges[eb * 2 + 1]]);
        out.normal = n;
        out.add(closest[0].add(closest[1]).mul(0.5f), -separation);
        return true;
    }

    /**
     * The edge along direction {@code d} whose two ends both reach {@code level} on {@code n} ({@code sign} 1 for the
     * top, -1 for the bottom), or -1.
     */
    private static int supportEdge(Polytope p, int d, Vec3 n, float level, float sign) {
        int best = -1;
        float lowest = Float.POSITIVE_INFINITY;
        for (int e = 0; e < p.edgeDirection.length; e++) {
            if (p.edgeDirection[e] != d) continue;
            float gap = Math.max(sign * (level - p.vertices[p.edges[e * 2]].dot(n)), sign * (level - p.vertices[p.edges[e * 2 + 1]].dot(n)));
            if (gap < lowest) {
                lowest = gap;
                best = e;
            }
        }
        return lowest <= 1e-3f ? best : -1;
    }

    // ---- Rounded shapes against a polytope --------------------------------------------------------------------------

    /**
     * A sphere ({@code p0} equal to {@code p1}) or capsule, given by its middle line and radius, against a polytope. The
     * gap between the line and the polytope gives the contact; a capsule lying flat on a face touches at both ends.
     */
    static void rounded(Vec3 p0, Vec3 p1, float r, Polytope b, float margin, Manifold out, MeshTriangle triangle) {
        Gjk.Result gap = Gjk.closest(p0, p1, b.vertices);
        if (gap.overlapping()) {
            deep(p0, p1, r, b, out, triangle);
            return;
        }
        if (gap.distance() - r > margin) return;
        Vec3 n = gap.onSet().sub(gap.onSegment()).mul(1f / gap.distance());
        boolean fixed = false;
        if (triangle != null) {
            Vec3 f = triangle.fix(n, gap.corners(), p0.add(p1).mul(0.5f));
            fixed = f != n;
            n = f;
        }
        int face = b.faceToward(n.negate());
        Vec3 fn = b.normals[face];
        Vec3 segment = p1.sub(p0);
        float length = segment.length();
        if (length > EPSILON && Math.abs(segment.dot(n)) < 0.1f * length && (fixed || fn.dot(n) < -0.98f)) {
            // Lying flat on a face: keep the part of the line over the face and touch at both its ends.
            float from = 0f, to = 1f;
            int[] loop = b.faces[face];
            Vec3 middle = Vec3.ZERO;
            for (int index : loop) middle = middle.add(b.vertices[index]);
            middle = middle.mul(1f / loop.length);
            for (int k = 0; k < loop.length && from <= to; k++) {
                Vec3 p = b.vertices[loop[k]], q = b.vertices[loop[(k + 1) % loop.length]];
                Vec3 side = q.sub(p).cross(fn);
                if (side.dot(middle.sub(p)) > 0f) side = side.negate();
                float start = side.dot(p0.sub(p)), step = side.dot(segment);
                if (Math.abs(step) < 1e-12f) {
                    if (start > 0f) from = 2f;
                    continue;
                }
                float t = -start / step;
                if (step > 0f) to = Math.min(to, t);
                else from = Math.max(from, t);
            }
            if (from <= to) {
                for (float s : from == to ? new float[]{from} : new float[]{from, to}) {
                    Vec3 p = p0.add(segment.mul(s));
                    float height = p.dot(fn) - b.offsets[face];
                    if (height - r <= margin) out.add(p.sub(fn.mul((r + height) * 0.5f)), r - height);
                }
                if (out.count > 0) {
                    out.normal = fn.negate();
                    return;
                }
            }
        }
        out.normal = n;
        if (fixed) {
            // Measured straight out of the triangle, as if the seam weren't there.
            Vec3 p = gap.onSegment();
            float height = p.dot(fn) - b.offsets[face];
            if (height - r <= margin) out.add(p.sub(fn.mul((r + height) * 0.5f)), r - height);
            return;
        }
        Vec3 onRounded = gap.onSegment().add(n.mul(r));
        out.add(onRounded.add(gap.onSet()).mul(0.5f), r - gap.distance());
    }

    /**
     * The middle line touches or crosses the polytope, so the nearest points say nothing. Instead push out along the
     * axis it overlaps least on: a face normal, or for a capsule the crossing of its line with an edge.
     */
    private static void deep(Vec3 p0, Vec3 p1, float r, Polytope b, Manifold out, MeshTriangle triangle) {
        Vec3 segment = p1.sub(p0);
        float best = Float.NEGATIVE_INFINITY;
        Vec3 n = Vec3.UP;
        int axes = b.normals.length + (segment.lengthSquared() > EPSILON ? b.directions.length : 0);
        for (int k = 0; k < axes; k++) {
            Vec3 axis;
            if (k < b.normals.length) {
                axis = b.normals[k];
            } else {
                axis = segment.cross(b.directions[k - b.normals.length]);
                float length = axis.length();
                if (length < 1e-4f) continue;
                axis = axis.mul(1f / length);
            }
            float lo = Math.min(p0.dot(axis), p1.dot(axis)), hi = Math.max(p0.dot(axis), p1.dot(axis));
            float ahead = b.min(axis) - hi, behind = lo - b.max(axis);
            float s = Math.max(ahead, behind);
            if (s > best) {
                best = s;
                n = ahead >= behind ? axis : axis.negate();
            }
        }
        if (triangle != null) n = triangle.fix(n, lowestCorners(b, n), p0.add(p1).mul(0.5f));
        out.normal = n;
        float bottom = b.min(n);
        float d0 = r - (bottom - p0.dot(n)), d1 = r - (bottom - p1.dot(n));
        float flat = 0.1f * Math.max(r, segment.length());
        for (Vec3 p : segment.lengthSquared() > EPSILON ? new Vec3[]{p0, p1} : new Vec3[]{p0}) {
            float depth = p == p0 ? d0 : d1;
            if (depth >= Math.max(d0, d1) - flat) out.add(p.add(n.mul(r - depth * 0.5f)), depth);
        }
    }

    // ---- Polytope against a plane ----------------------------------------------------------------------------------

    static void plane(Polytope a, Vec3 normal, Vec3 point, float margin, Manifold out) {
        out.normal = normal.negate();
        for (Vec3 corner : a.vertices) {
            float separation = corner.sub(point).dot(normal);
            if (separation <= margin) out.add(corner.sub(normal.mul(separation * 0.5f)), -separation);
        }
    }
}
