package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * Exact contact tests between two placed shapes. Each test fills a {@link Manifold} with the normal from the first
 * shape to the second and the points where they touch, or leaves it empty when they are farther apart than the margin.
 */
final class Narrowphase {
    private static final float EPSILON = 1e-6f;
    private static final int GOLDEN_STEPS = 40;

    private Narrowphase() {
    }

    /**
     * Fills {@code out} with the contact between {@code a} and {@code b}; true when there is one. A mesh can touch in
     * many places at once, so it goes through {@link MeshCollision} instead.
     */
    static boolean collide(Shape a, Pose pa, Shape b, Pose pb, float margin, Manifold out) {
        if (a instanceof MeshShape || b instanceof MeshShape) throw new IllegalArgumentException("Mesh contacts go through MeshCollision");
        out.reset();
        if (rank(a) > rank(b)) {
            if (collide(b, pb, a, pa, margin, out)) {
                out.normal = out.normal.negate();
                return true;
            }
            return false;
        }
        switch (a) {
            case SphereShape s -> {
                switch (b) {
                    case SphereShape t -> sphereSphere(s.radius(), pa.position(), t.radius(), pb.position(), margin, out);
                    case CapsuleShape t -> {
                        Vec3 q = closestOnSegment(capsuleEnd(t, pb, -1), capsuleEnd(t, pb, 1), pa.position());
                        sphereSphere(s.radius(), pa.position(), t.radius(), q, margin, out);
                    }
                    case BoxShape t -> sphereBox(s.radius(), pa.position(), t.halfExtents(), pb, margin, out);
                    case ConvexHullShape t -> PolytopeCollision.rounded(pa.position(), pa.position(), s.radius(), t.polytope().placed(pb), margin, out, null);
                    case PlaneShape t -> spherePlane(s.radius(), pa.position(), t, pb, margin, out);
                    case MeshShape _ -> throw new IllegalStateException();
                }
            }
            case CapsuleShape s -> {
                switch (b) {
                    case CapsuleShape t -> capsuleCapsule(s, pa, t, pb, margin, out);
                    case BoxShape t -> capsuleBox(s, pa, t.halfExtents(), pb, margin, out);
                    case ConvexHullShape t -> PolytopeCollision.rounded(capsuleEnd(s, pa, -1), capsuleEnd(s, pa, 1), s.radius(), t.polytope().placed(pb), margin, out, null);
                    case PlaneShape t -> capsulePlane(s, pa, t, pb, margin, out);
                    default -> throw new IllegalStateException();
                }
            }
            case BoxShape s -> {
                switch (b) {
                    case BoxShape t -> boxBox(s.halfExtents(), pa, t.halfExtents(), pb, margin, out);
                    case ConvexHullShape t -> PolytopeCollision.polytopes(Polytope.box(s.halfExtents()).placed(pa), t.polytope().placed(pb), margin, out, null);
                    case PlaneShape t -> boxPlane(s.halfExtents(), pa, t, pb, margin, out);
                    default -> throw new IllegalStateException();
                }
            }
            case ConvexHullShape s -> {
                switch (b) {
                    case ConvexHullShape t -> PolytopeCollision.polytopes(s.polytope().placed(pa), t.polytope().placed(pb), margin, out, null);
                    case PlaneShape t -> PolytopeCollision.plane(s.polytope().placed(pa), pb.rotate(t.normal()), pb.position(), margin, out);
                    default -> throw new IllegalStateException();
                }
            }
            case PlaneShape _ -> {
                // Two endless planes either cross everywhere or never; neither is a useful contact.
            }
            case MeshShape _ -> throw new IllegalStateException();
        }
        out.reduce();
        return out.count > 0;
    }

    private static int rank(Shape shape) {
        return switch (shape) {
            case SphereShape _ -> 0;
            case CapsuleShape _ -> 1;
            case BoxShape _ -> 2;
            case ConvexHullShape _ -> 3;
            case PlaneShape _ -> 4;
            case MeshShape _ -> 5;
        };
    }

    // ---- Spheres --------------------------------------------------------------------------------------------------

    private static void sphereSphere(float ra, Vec3 ca, float rb, Vec3 cb, float margin, Manifold out) {
        Vec3 d = cb.sub(ca);
        float distance = d.length();
        float separation = distance - ra - rb;
        if (separation > margin) return;
        Vec3 n = distance > EPSILON ? d.mul(1f / distance) : Vec3.UP;
        Vec3 onA = ca.add(n.mul(ra)), onB = cb.sub(n.mul(rb));
        out.normal = n;
        out.add(onA.add(onB).mul(0.5f), -separation);
    }

    private static void sphereBox(float radius, Vec3 center, Vec3 half, Pose box, float margin, Manifold out) {
        Vec3 local = box.toLocal(center);
        Vec3 clamped = clamp(local, half);
        if (!local.equals(clamped)) {
            Vec3 d = local.sub(clamped);
            float distance = d.length();
            float separation = distance - radius;
            if (separation > margin) return;
            Vec3 outward = box.rotate(d.mul(1f / distance)); // from the box toward the sphere
            Vec3 onBox = box.toWorld(clamped), onSphere = center.sub(outward.mul(radius));
            out.normal = outward.negate();
            out.add(onBox.add(onSphere).mul(0.5f), -separation);
            return;
        }
        // The centre is inside: push out through the nearest face.
        int axis = 0;
        float least = half.x() - Math.abs(local.x());
        for (int i = 1; i < 3; i++) {
            float room = get(half, i) - Math.abs(get(local, i));
            if (room < least) {
                least = room;
                axis = i;
            }
        }
        float sign = get(local, axis) >= 0f ? 1f : -1f;
        Vec3 outward = box.axis(axis).mul(sign);
        out.normal = outward.negate();
        out.add(center.add(outward.mul(least * 0.5f - radius * 0.5f)), least + radius);
    }

    private static void spherePlane(float radius, Vec3 center, PlaneShape plane, Pose pose, float margin, Manifold out) {
        Vec3 n = pose.rotate(plane.normal());
        float height = center.sub(pose.position()).dot(n);
        float separation = height - radius;
        if (separation > margin) return;
        out.normal = n.negate();
        out.add(center.sub(n.mul(height * 0.5f + radius * 0.5f)), -separation);
    }

    // ---- Capsules -------------------------------------------------------------------------------------------------

    private static Vec3 capsuleEnd(CapsuleShape capsule, Pose pose, float side) {
        return pose.toWorld(new Vec3(0f, capsule.halfHeight() * side, 0f));
    }

    private static void capsuleCapsule(CapsuleShape a, Pose pa, CapsuleShape b, Pose pb, float margin, Manifold out) {
        Vec3 a0 = capsuleEnd(a, pa, -1), a1 = capsuleEnd(a, pa, 1);
        Vec3 b0 = capsuleEnd(b, pb, -1), b1 = capsuleEnd(b, pb, 1);
        Vec3 da = a1.sub(a0), db = b1.sub(b0);
        float la = da.lengthSquared(), lb = db.lengthSquared();
        if (la > EPSILON && lb > EPSILON && da.cross(db).lengthSquared() < 1e-4f * la * lb) {
            // Lying side by side: touch at both ends of the overlap so the pair doesn't rock.
            float t0 = Math.clamp(b0.sub(a0).dot(da) / la, 0f, 1f), t1 = Math.clamp(b1.sub(a0).dot(da) / la, 0f, 1f);
            if (Math.abs(t1 - t0) * Math.sqrt(la) > 1e-3f) {
                for (float t : new float[]{t0, t1}) {
                    Vec3 p = a0.add(da.mul(t));
                    Vec3 q = closestOnSegment(b0, b1, p);
                    Manifold one = new Manifold();
                    sphereSphere(a.radius(), p, b.radius(), q, margin, one);
                    if (one.count > 0) {
                        out.normal = one.normal;
                        out.add(one.points[0], one.depths[0]);
                    }
                }
                return;
            }
        }
        Vec3[] closest = closestBetweenSegments(a0, a1, b0, b1);
        sphereSphere(a.radius(), closest[0], b.radius(), closest[1], margin, out);
    }

    private static void capsulePlane(CapsuleShape capsule, Pose pose, PlaneShape plane, Pose planePose, float margin, Manifold out) {
        spherePlane(capsule.radius(), capsuleEnd(capsule, pose, -1), plane, planePose, margin, out);
        if (capsule.halfHeight() > 0f) spherePlane(capsule.radius(), capsuleEnd(capsule, pose, 1), plane, planePose, margin, out);
    }

    private static void capsuleBox(CapsuleShape capsule, Pose pose, Vec3 half, Pose box, float margin, Manifold out) {
        float r = capsule.radius();
        Vec3 p0 = box.toLocal(capsuleEnd(capsule, pose, -1)), p1 = box.toLocal(capsuleEnd(capsule, pose, 1));
        Vec3 dir = p1.sub(p0);
        // The signed distance to a box is convex, so along the segment it has one lowest point to search for.
        float lo = 0f, hi = 1f;
        final float g = 0.618034f;
        float m1 = hi - g * (hi - lo), m2 = lo + g * (hi - lo);
        float f1 = signedDistance(p0.add(dir.mul(m1)), half), f2 = signedDistance(p0.add(dir.mul(m2)), half);
        for (int i = 0; i < GOLDEN_STEPS && dir.lengthSquared() > EPSILON; i++) {
            if (f1 < f2) {
                hi = m2;
                m2 = m1;
                f2 = f1;
                m1 = hi - g * (hi - lo);
                f1 = signedDistance(p0.add(dir.mul(m1)), half);
            } else {
                lo = m1;
                m1 = m2;
                f1 = f2;
                m2 = lo + g * (hi - lo);
                f2 = signedDistance(p0.add(dir.mul(m2)), half);
            }
        }
        float t = (lo + hi) * 0.5f;
        float[] ends = {0f, 1f, t};
        float bestT = t, best = signedDistance(p0.add(dir.mul(t)), half);
        for (float e : ends) {
            float d = signedDistance(p0.add(dir.mul(e)), half);
            if (d < best) {
                best = d;
                bestT = e;
            }
        }
        if (best - r > margin) return;

        if (best < 0f) {
            capsuleDeepInBox(p0, p1, r, half, box, out);
            return;
        }
        Vec3 q = p0.add(dir.mul(bestT));
        Vec3 clamped = clamp(q, half);
        Vec3 outwardLocal;
        int faceAxis = -1;
        if (!q.equals(clamped)) {
            outwardLocal = q.sub(clamped).normalized();
            int clampedAxes = 0;
            for (int i = 0; i < 3; i++) {
                if (get(q, i) != get(clamped, i)) {
                    clampedAxes++;
                    faceAxis = i;
                }
            }
            if (clampedAxes != 1) faceAxis = -1;
        } else {
            faceAxis = 0;
            float least = half.x() - Math.abs(q.x());
            for (int i = 1; i < 3; i++) {
                float room = get(half, i) - Math.abs(get(q, i));
                if (room < least) {
                    least = room;
                    faceAxis = i;
                }
            }
            outwardLocal = unit(faceAxis, get(q, faceAxis) >= 0f ? 1f : -1f);
        }
        out.normal = box.rotate(outwardLocal).negate();

        if (faceAxis >= 0 && dir.lengthSquared() > EPSILON && Math.abs(get(dir, faceAxis)) < 0.1f * dir.length()) {
            // Lying flat on a face: keep the part of the segment over the face and touch at both its ends.
            float sign = get(outwardLocal, faceAxis);
            float from = 0f, to = 1f;
            for (int i = 0; i < 3 && from <= to; i++) {
                if (i == faceAxis) continue;
                float start = get(p0, i), step = get(dir, i), h = get(half, i);
                if (Math.abs(step) < EPSILON) {
                    if (Math.abs(start) > h) from = 2f;
                    continue;
                }
                float ta = (-h - start) / step, tb = (h - start) / step;
                from = Math.max(from, Math.min(ta, tb));
                to = Math.min(to, Math.max(ta, tb));
            }
            if (from <= to) {
                float faceHeight = get(half, faceAxis);
                for (float s : from == to ? new float[]{from} : new float[]{from, to}) {
                    Vec3 p = p0.add(dir.mul(s));
                    float separation = get(p, faceAxis) * sign - faceHeight - r;
                    if (separation <= margin) {
                        Vec3 onCapsule = p.sub(outwardLocal.mul(r)), onBox = withAxis(p, faceAxis, faceHeight * sign);
                        out.add(box.toWorld(onCapsule.add(onBox).mul(0.5f)), -separation);
                    }
                }
                if (out.count > 0) return;
            }
        }
        Vec3 onCapsule = q.sub(outwardLocal.mul(r));
        Vec3 onBox = q.equals(clamped) ? withAxis(q, faceAxis, get(half, faceAxis) * get(outwardLocal, faceAxis)) : clamped;
        out.add(box.toWorld(onCapsule.add(onBox).mul(0.5f)), r - best);
    }

    /**
     * The capsule's middle line is inside the box, so the nearest face to one point says little. Instead push it out
     * the way it overlaps least along the box's axes.
     */
    private static void capsuleDeepInBox(Vec3 p0, Vec3 p1, float r, Vec3 half, Pose box, Manifold out) {
        int axis = 0;
        float sign = 1f, least = Float.POSITIVE_INFINITY;
        for (int k = 0; k < 3; k++) {
            float lo = Math.min(get(p0, k), get(p1, k)) - r, hi = Math.max(get(p0, k), get(p1, k)) + r, h = get(half, k);
            if (h - lo < least) {
                least = h - lo;
                axis = k;
                sign = 1f;
            }
            if (hi + h < least) {
                least = hi + h;
                axis = k;
                sign = -1f;
            }
        }
        Vec3 outward = unit(axis, sign);
        out.normal = box.rotate(outward).negate();
        float h = get(half, axis);
        float d0 = h - sign * get(p0, axis) + r, d1 = h - sign * get(p1, axis) + r;
        float flat = 0.1f * Math.max(least, p1.sub(p0).length());
        for (Vec3 p : new Vec3[]{p0, p1}) {
            float depth = p == p0 ? d0 : d1;
            if (depth >= Math.max(d0, d1) - flat) out.add(box.toWorld(p.sub(outward.mul(r))), depth);
        }
    }

    // ---- Boxes ----------------------------------------------------------------------------------------------------

    private static void boxPlane(Vec3 half, Pose box, PlaneShape plane, Pose planePose, float margin, Manifold out) {
        Vec3 n = planePose.rotate(plane.normal());
        out.normal = n.negate();
        for (int i = 0; i < 8; i++) {
            Vec3 corner = box.toWorld(new Vec3((i & 1) == 0 ? -half.x() : half.x(), (i & 2) == 0 ? -half.y() : half.y(), (i & 4) == 0 ? -half.z() : half.z()));
            float separation = corner.sub(planePose.position()).dot(n);
            if (separation <= margin) out.add(corner.sub(n.mul(separation * 0.5f)), -separation);
        }
    }

    /**
     * Two boxes by the separating axis test: the face normals of each and the crossings of their edges. If any axis
     * holds them apart there is no contact; otherwise the axis they overlap least on is the normal. Face contacts clip
     * the other box's nearest face to the touching face, which gives up to four points for a box resting flat.
     */
    private static void boxBox(Vec3 ha, Pose pa, Vec3 hb, Pose pb, float margin, Manifold out) {
        Vec3 d = pb.position().sub(pa.position());
        Vec3[] a = {pa.axis(0), pa.axis(1), pa.axis(2)};
        Vec3[] b = {pb.axis(0), pb.axis(1), pb.axis(2)};
        float[][] abs = new float[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) abs[i][j] = Math.abs(a[i].dot(b[j])) + EPSILON;

        float faceA = Float.NEGATIVE_INFINITY, faceB = Float.NEGATIVE_INFINITY, edge = Float.NEGATIVE_INFINITY;
        int faceAAxis = 0, faceBAxis = 0, edgeA = 0, edgeB = 0;
        Vec3 edgeNormal = Vec3.UP;
        for (int i = 0; i < 3; i++) {
            float s = Math.abs(d.dot(a[i])) - (get(ha, i) + get(hb, 0) * abs[i][0] + get(hb, 1) * abs[i][1] + get(hb, 2) * abs[i][2]);
            if (s > margin) return;
            if (s > faceA) {
                faceA = s;
                faceAAxis = i;
            }
        }
        for (int j = 0; j < 3; j++) {
            float s = Math.abs(d.dot(b[j])) - (get(ha, 0) * abs[0][j] + get(ha, 1) * abs[1][j] + get(ha, 2) * abs[2][j] + get(hb, j));
            if (s > margin) return;
            if (s > faceB) {
                faceB = s;
                faceBAxis = j;
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vec3 axis = a[i].cross(b[j]);
                float length = axis.length();
                if (length < 1e-4f) continue; // parallel edges: the face axes already cover this
                axis = axis.mul(1f / length);
                float projA = get(ha, 0) * Math.abs(a[0].dot(axis)) + get(ha, 1) * Math.abs(a[1].dot(axis)) + get(ha, 2) * Math.abs(a[2].dot(axis));
                float projB = get(hb, 0) * Math.abs(b[0].dot(axis)) + get(hb, 1) * Math.abs(b[1].dot(axis)) + get(hb, 2) * Math.abs(b[2].dot(axis));
                float along = d.dot(axis);
                float s = Math.abs(along) - projA - projB;
                if (s > margin) return;
                if (s > edge) {
                    edge = s;
                    edgeA = i;
                    edgeB = j;
                    edgeNormal = along >= 0f ? axis : axis.negate();
                }
            }
        }

        // Faces give steadier contacts, so an edge or the other box's face has to be clearly better to win.
        float scale = 0.01f * Math.min(Math.min(Math.min(ha.x(), ha.y()), ha.z()), Math.min(Math.min(hb.x(), hb.y()), hb.z()));
        // (A margin of 5 % of the depth, either way round: when the boxes are still apart, 95 % of the gap is a smaller
        // number than the gap, and an equal edge would have won every time and reduced a face to one point.)
        boolean useB = faceB > faceA + 0.05f * Math.abs(faceA) + scale;
        float bestFace = useB ? faceB : faceA;
        if (edge > bestFace + 0.05f * Math.abs(bestFace) + 2f * scale) {
            boxEdges(ha, pa, a, edgeA, hb, pb, b, edgeB, edgeNormal, edge, out);
            return;
        }
        if (useB) {
            Vec3 n = b[faceBAxis].mul(d.dot(b[faceBAxis]) >= 0f ? -1f : 1f); // from B toward A
            clipFaces(hb, pb, b, faceBAxis, n, ha, pa, a, margin, out);
            out.normal = n.negate();
        } else {
            Vec3 n = a[faceAAxis].mul(d.dot(a[faceAAxis]) >= 0f ? 1f : -1f); // from A toward B
            clipFaces(ha, pa, a, faceAAxis, n, hb, pb, b, margin, out);
            out.normal = n;
        }
    }

    /**
     * Clips the incident box's face that turns most against {@code n} to the sides of the reference box's face, keeping
     * the corners that reach the reference face.
     */
    private static void clipFaces(Vec3 hr, Pose pr, Vec3[] r, int axis, Vec3 n, Vec3 hi, Pose pi, Vec3[] in, float margin, Manifold out) {
        int incident = 0;
        float most = Float.POSITIVE_INFINITY;
        for (int k = 0; k < 3; k++) {
            float dot = -Math.abs(in[k].dot(n));
            if (dot < most) {
                most = dot;
                incident = k;
            }
        }
        Vec3 incidentNormal = in[incident].mul(in[incident].dot(n) > 0f ? -1f : 1f);
        Vec3 center = pi.position().add(incidentNormal.mul(get(hi, incident)));
        int u = (incident + 1) % 3, v = (incident + 2) % 3;
        Vec3 du = in[u].mul(get(hi, u)), dv = in[v].mul(get(hi, v));
        Vec3[] polygon = {center.add(du).add(dv), center.sub(du).add(dv), center.sub(du).sub(dv), center.add(du).sub(dv)};
        int size = 4;

        Vec3 faceCenter = pr.position().add(n.mul(get(hr, axis)));
        for (int side = 1; side <= 2 && size > 0; side++) {
            int k = (axis + side) % 3;
            Vec3 sideAxis = r[k];
            float limit = get(hr, k);
            for (float sign : new float[]{1f, -1f}) {
                Vec3 planeNormal = sideAxis.mul(sign);
                float offset = planeNormal.dot(pr.position()) + limit;
                Vec3[] clipped = new Vec3[size * 2];
                int count = 0;
                for (int i = 0; i < size; i++) {
                    Vec3 p = polygon[i], q = polygon[(i + 1) % size];
                    float dp = planeNormal.dot(p) - offset, dq = planeNormal.dot(q) - offset;
                    if (dp <= 0f) clipped[count++] = p;
                    if ((dp < 0f && dq > 0f) || (dp > 0f && dq < 0f)) clipped[count++] = p.lerp(q, dp / (dp - dq));
                }
                polygon = clipped;
                size = count;
                if (size == 0) break;
            }
        }
        for (int i = 0; i < size; i++) {
            Vec3 p = polygon[i];
            float separation = p.sub(faceCenter).dot(n);
            if (separation <= margin) out.add(p.sub(n.mul(separation * 0.5f)), -separation);
        }
    }

    /**
     * Edge against edge: one point, midway between the closest points of the two touching edges.
     */
    private static void boxEdges(Vec3 ha, Pose pa, Vec3[] a, int ia, Vec3 hb, Pose pb, Vec3[] b, int ib, Vec3 n, float separation, Manifold out) {
        Vec3 onA = pa.position(), onB = pb.position();
        for (int k = 0; k < 3; k++) {
            if (k != ia) onA = onA.add(a[k].mul(get(ha, k) * (a[k].dot(n) >= 0f ? 1f : -1f)));
            if (k != ib) onB = onB.add(b[k].mul(get(hb, k) * (b[k].dot(n) >= 0f ? -1f : 1f)));
        }
        Vec3 ea = a[ia].mul(get(ha, ia)), eb = b[ib].mul(get(hb, ib));
        Vec3[] closest = closestBetweenSegments(onA.sub(ea), onA.add(ea), onB.sub(eb), onB.add(eb));
        out.normal = n;
        out.add(closest[0].add(closest[1]).mul(0.5f), -separation);
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    static Vec3 closestOnSegment(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.sub(a);
        float length = ab.lengthSquared();
        if (length < EPSILON) return a;
        return a.add(ab.mul(Math.clamp(p.sub(a).dot(ab) / length, 0f, 1f)));
    }

    /**
     * The closest points between segments p1-q1 and p2-q2 (from Ericson, Real-Time Collision Detection, 5.1.9).
     */
    static Vec3[] closestBetweenSegments(Vec3 p1, Vec3 q1, Vec3 p2, Vec3 q2) {
        Vec3 d1 = q1.sub(p1), d2 = q2.sub(p2), r = p1.sub(p2);
        float a = d1.lengthSquared(), e = d2.lengthSquared(), f = d2.dot(r);
        float s, t;
        if (a <= EPSILON && e <= EPSILON) {
            s = t = 0f;
        } else if (a <= EPSILON) {
            s = 0f;
            t = Math.clamp(f / e, 0f, 1f);
        } else {
            float c = d1.dot(r);
            if (e <= EPSILON) {
                t = 0f;
                s = Math.clamp(-c / a, 0f, 1f);
            } else {
                float b = d1.dot(d2), denom = a * e - b * b;
                s = denom > EPSILON ? Math.clamp((b * f - c * e) / denom, 0f, 1f) : 0f;
                t = (b * s + f) / e;
                if (t < 0f) {
                    t = 0f;
                    s = Math.clamp(-c / a, 0f, 1f);
                } else if (t > 1f) {
                    t = 1f;
                    s = Math.clamp((b - c) / a, 0f, 1f);
                }
            }
        }
        return new Vec3[]{p1.add(d1.mul(s)), p2.add(d2.mul(t))};
    }

    /**
     * Distance from a point to a box centred on the origin, negative inside.
     */
    private static float signedDistance(Vec3 p, Vec3 half) {
        float dx = Math.abs(p.x()) - half.x(), dy = Math.abs(p.y()) - half.y(), dz = Math.abs(p.z()) - half.z();
        float ox = Math.max(dx, 0f), oy = Math.max(dy, 0f), oz = Math.max(dz, 0f);
        return (float) Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(dx, Math.max(dy, dz)), 0f);
    }

    private static Vec3 clamp(Vec3 p, Vec3 half) {
        return new Vec3(Math.clamp(p.x(), -half.x(), half.x()), Math.clamp(p.y(), -half.y(), half.y()), Math.clamp(p.z(), -half.z(), half.z()));
    }

    static float get(Vec3 v, int axis) {
        return switch (axis) {
            case 0 -> v.x();
            case 1 -> v.y();
            default -> v.z();
        };
    }

    private static Vec3 withAxis(Vec3 v, int axis, float value) {
        return switch (axis) {
            case 0 -> new Vec3(value, v.y(), v.z());
            case 1 -> new Vec3(v.x(), value, v.z());
            default -> new Vec3(v.x(), v.y(), value);
        };
    }

    private static Vec3 unit(int axis, float sign) {
        return withAxis(Vec3.ZERO, axis, sign);
    }
}
