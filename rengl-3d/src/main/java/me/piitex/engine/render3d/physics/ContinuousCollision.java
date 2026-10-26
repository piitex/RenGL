package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

import java.util.List;

/**
 * Continuous collision: how far along its step a fast body gets before it touches something that doesn't move. The
 * contacts of {@link ContactSolver} reach a little past a body's box (speculative contacts), which holds a body that
 * travels a few metres in a step, but not a bullet that travels dozens or a thin sheet that has a wall to cross at a
 * tilt; they only see what is within their margin. This walks the shape along the motion the step is about to make,
 * as far as the nearest it could get without touching (conservative advancement: the gap by GJK, divided by the
 * fastest the shape's points can close it), and reports the fraction of the step at which it first comes within
 * {@link #SKIN} of anything. The body is then moved only that far, so the next step finds the contact in range and the
 * solver does what it always does.
 * <p>
 * Only the bodies that don't move are swept against (static ones, and kinematic ones at rest), and only a body whose
 * step is long for how thin it is. A body already touching a surface isn't held back by it, and one the solver has
 * already stopped at the surface isn't either, so resting and landing are as they were.
 */
final class ContinuousCollision {
    /**
     * How near a surface a swept body is stopped, in metres: inside the speculative margin, so the next step has a
     * contact for it.
     */
    static final float SKIN = 0.005f;
    private static final int MAX_ITERATIONS = 32;

    private ContinuousCollision() {
    }

    /**
     * The gap from a moving shape's corners (grown by its radius) to something, and the unit direction from one to
     * the other when the gap is positive.
     */
    private interface Measure {
        float gap(Vec3[] corners, float radius, Vec3[] direction);
    }

    /**
     * The motion a body is about to make: its centre of mass moves in a straight line while it turns by the shortest way,
     * both at a steady rate.
     */
    private static final class Sweep {
        final Vec3 start, move, localCenter;
        final Quat from, to;
        final float angle, reach;
        final Vec3 axis;
        final List<BodyShape> shapes;
        final Vec3[][] corners;
        final float[] radius;

        Sweep(RigidBody body, float dt) {
            start = new Vec3(body.cx, body.cy, body.cz);
            move = new Vec3((body.vx + body.pvx) * dt, (body.vy + body.pvy) * dt, (body.vz + body.pvz) * dt);
            localCenter = body.getLocalCenterOfMass();
            from = body.getRotation();
            to = body.orientationAfter(dt);
            Quat turn = to.mul(from.conjugate());
            if (turn.w() < 0f) turn = new Quat(-turn.x(), -turn.y(), -turn.z(), -turn.w());
            float s = (float) Math.sqrt(turn.x() * turn.x() + turn.y() * turn.y() + turn.z() * turn.z());
            angle = 2f * (float) Math.atan2(s, turn.w());
            axis = s > 1e-6f ? new Vec3(turn.x() / s, turn.y() / s, turn.z() / s) : Vec3.ZERO;
            reach = body.reach();
            shapes = body.placedShapes();
            corners = new Vec3[shapes.size()][];
            radius = new float[shapes.size()];
            for (int i = 0; i < corners.length; i++) {
                switch (shapes.get(i).shape()) {
                    case SphereShape s0 -> {
                        corners[i] = new Vec3[]{Vec3.ZERO};
                        radius[i] = s0.radius();
                    }
                    case CapsuleShape c -> {
                        corners[i] = new Vec3[]{new Vec3(0f, c.halfHeight(), 0f), new Vec3(0f, -c.halfHeight(), 0f)};
                        radius[i] = c.radius();
                    }
                    case BoxShape b -> corners[i] = Polytope.box(b.halfExtents()).vertices;
                    case ConvexHullShape h -> corners[i] = h.polytope().vertices;
                    case PlaneShape _, MeshShape _ -> corners[i] = new Vec3[0]; // a dynamic body can't have one
                }
            }
        }

        /**
         * The corners of shape {@code i} when the step is {@code t} of the way through.
         */
        Vec3[] cornersAt(int i, float t) {
            Quat rotation = angle == 0f ? from : from.slerp(to, t);
            Vec3 origin = start.add(move.mul(t)).sub(rotation.rotate(localCenter));
            BodyShape shape = shapes.get(i);
            Pose pose = new Pose(origin.add(rotation.rotate(shape.offset())), rotation.mul(shape.rotation()));
            Vec3[] local = corners[i], world = new Vec3[local.length];
            for (int k = 0; k < world.length; k++) world[k] = pose.toWorld(local[k]);
            return world;
        }

        /**
         * The farthest anything on the body can get toward a surface in the direction {@code toward}, for the whole
         * step: the centre's travel along it plus the sweep of the body's farthest point as it turns.
         */
        float rate(Vec3 toward) {
            float turn = angle == 0f ? 0f : reach * angle * toward.cross(axis).length();
            return move.dot(toward) + turn;
        }

        Bounds area() {
            float r = reach + SKIN;
            Vec3 end = start.add(move);
            return new Bounds(new Vec3(Math.min(start.x(), end.x()) - r, Math.min(start.y(), end.y()) - r, Math.min(start.z(), end.z()) - r),
                    new Vec3(Math.max(start.x(), end.x()) + r, Math.max(start.y(), end.y()) + r, Math.max(start.z(), end.z()) + r));
        }
    }

    /**
     * The fraction of the step, from 0 to 1, the body can move before it first comes within {@link #SKIN} of
     * something that doesn't move; 1 when nothing is in the way.
     */
    static float timeOfImpact(PhysicsWorld world, RigidBody body, float dt) {
        Sweep sweep = new Sweep(body, dt);
        Bounds area = sweep.area();
        float[] best = {1f};
        world.tree.query(area, other -> {
            if (!blocks(body, other)) return;
            for (int j = 0; j < other.shapeCount(); j++) {
                if (Broadphase.overlap(area, other.shapeBounds(j))) against(sweep, other.collisionShape(j), other.shapePose(j), best);
            }
        });
        return best[0];
    }

    /**
     * Whether {@code body} can be stopped by {@code other}: something that stays where it is, that it collides with.
     */
    private static boolean blocks(RigidBody body, RigidBody other) {
        if (other == body || other.isDynamic() || other.isTrigger() || body.isTrigger()) return false;
        if (other.getType() == BodyType.KINEMATIC && other.isMoving()) return false;
        if (!Broadphase.layersAgree(body, other)) return false;
        return body.joints.isEmpty() || other.joints.isEmpty() || !PhysicsWorld.jointedApart(body, other);
    }

    private static void against(Sweep sweep, Shape shape, Pose pose, float[] best) {
        switch (shape) {
            case SphereShape s -> convex(sweep, new Vec3[]{pose.position()}, s.radius(), best);
            case CapsuleShape c -> {
                Vec3 up = pose.axis(1).mul(c.halfHeight());
                convex(sweep, new Vec3[]{pose.position().add(up), pose.position().sub(up)}, c.radius(), best);
            }
            case BoxShape b -> convex(sweep, place(Polytope.box(b.halfExtents()).vertices, pose), 0f, best);
            case ConvexHullShape h -> convex(sweep, place(h.polytope().vertices, pose), 0f, best);
            case PlaneShape p -> plane(sweep, pose.position(), pose.rotate(p.normal()), best);
            case MeshShape m -> mesh(sweep, m, pose, best);
        }
    }

    private static Vec3[] place(Vec3[] local, Pose pose) {
        Vec3[] world = new Vec3[local.length];
        for (int i = 0; i < world.length; i++) world[i] = pose.toWorld(local[i]);
        return world;
    }

    private static void convex(Sweep sweep, Vec3[] other, float radius, float[] best) {
        Measure measure = (corners, r, direction) -> {
            Gjk.Result result = Gjk.closest(corners, other);
            if (result.overlapping()) return result.distance() - r - radius;
            direction[0] = result.onSet().sub(result.onSegment()).mul(1f / result.distance());
            return result.distance() - r - radius;
        };
        for (int i = 0; i < sweep.shapes.size(); i++) best[0] = Math.min(best[0], firstTouch(sweep, i, measure, best[0]));
    }

    private static void plane(Sweep sweep, Vec3 point, Vec3 normal, float[] best) {
        Measure measure = (corners, r, direction) -> {
            float low = Float.POSITIVE_INFINITY;
            for (Vec3 corner : corners) low = Math.min(low, corner.sub(point).dot(normal));
            direction[0] = normal.negate();
            return low - r;
        };
        for (int i = 0; i < sweep.shapes.size(); i++) best[0] = Math.min(best[0], firstTouch(sweep, i, measure, best[0]));
    }

    private static void mesh(Sweep sweep, MeshShape mesh, Pose pose, float[] best) {
        // The swept box in the mesh's own space, to pick the triangles it could reach.
        Bounds area = sweep.area();
        float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int k = 0; k < 8; k++) {
            Vec3 p = pose.toLocal(new Vec3((k & 1) == 0 ? area.min().x() : area.max().x(), (k & 2) == 0 ? area.min().y() : area.max().y(),
                    (k & 4) == 0 ? area.min().z() : area.max().z()));
            lo[0] = Math.min(lo[0], p.x());
            lo[1] = Math.min(lo[1], p.y());
            lo[2] = Math.min(lo[2], p.z());
            hi[0] = Math.max(hi[0], p.x());
            hi[1] = Math.max(hi[1], p.y());
            hi[2] = Math.max(hi[2], p.z());
        }
        mesh.tree().overlapping(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], t -> {
            Vec3[] triangle = {pose.toWorld(mesh.corner(t, 0)), pose.toWorld(mesh.corner(t, 1)), pose.toWorld(mesh.corner(t, 2))};
            convex(sweep, triangle, 0f, best);
        });
    }

    /**
     * Moves shape {@code i} along the step in jumps that can't reach the target (the gap over the fastest it can close
     * it) until it is within {@link #SKIN}, and returns the fraction of the step that took; {@code limit} when it
     * doesn't get there before then. A shape that starts out touching, or whose step the solver has already cut so it ends at
     * the surface, is not held back.
     */
    private static float firstTouch(Sweep sweep, int i, Measure measure, float limit) {
        Vec3[] direction = new Vec3[1];
        float t = 0f, closing = 0f;
        for (int n = 0; n < MAX_ITERATIONS; n++) {
            float gap = measure.gap(sweep.cornersAt(i, t), sweep.radius[i], direction);
            if (gap <= SKIN * 1.5f) {
                if (t == 0f) return limit;
                // Reached it. A step that goes no further than the skin beyond is the solver's own landing, not a miss.
                return (1f - t) * closing <= 2f * SKIN ? limit : t;
            }
            closing = sweep.rate(direction[0]);
            if (closing <= 1e-6f) return limit; // moving away, or along it
            t += (gap - SKIN) / closing;
            if (t >= limit) return limit;
        }
        return t; // still closing in after all the jumps: stop where it is, which is short of it
    }
}
