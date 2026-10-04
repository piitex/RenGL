package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;

/**
 * Exact ray tests against placed shapes. A ray that starts inside a solid shape doesn't hit it: there is no surface to
 * report, and a ray cast from inside a body (a character looking out) shouldn't find that body first.
 */
final class Raycast {
    private Raycast() {
    }

    /**
     * The distance and normal of the hit, or null for a miss or a hit beyond {@code maxDistance}.
     */
    record Result(float distance, Vec3 normal) {
    }

    static Result cast(Shape shape, Pose pose, Ray ray, float maxDistance) {
        Vec3 o = pose.toLocal(ray.origin()), d = pose.unrotate(ray.direction());
        Result local = switch (shape) {
            case SphereShape s -> sphere(o, d, Vec3.ZERO, s.radius());
            case BoxShape b -> box(o, d, b.halfExtents());
            case CapsuleShape c -> capsule(o, d, c.radius(), c.halfHeight());
            case PlaneShape p -> plane(o, d, p.normal());
        };
        if (local == null || local.distance > maxDistance) return null;
        return new Result(local.distance, pose.rotate(local.normal));
    }

    private static Result sphere(Vec3 o, Vec3 d, Vec3 center, float radius) {
        Vec3 oc = o.sub(center);
        float a = d.lengthSquared(), b = oc.dot(d), c = oc.lengthSquared() - radius * radius;
        if (c < 0f || a < 1e-20f) return null;
        float disc = b * b - a * c;
        if (disc < 0f) return null;
        float t = (-b - (float) Math.sqrt(disc)) / a;
        if (t < 0f) return null;
        return new Result(t, oc.add(d.mul(t)).mul(1f / radius));
    }

    private static Result box(Vec3 o, Vec3 d, Vec3 half) {
        float near = Float.NEGATIVE_INFINITY, far = Float.POSITIVE_INFINITY;
        int axis = -1;
        float sign = 0f;
        boolean inside = true;
        for (int k = 0; k < 3; k++) {
            float ok = Narrowphase.get(o, k), dk = Narrowphase.get(d, k), h = Narrowphase.get(half, k);
            if (Math.abs(ok) > h) inside = false;
            if (Math.abs(dk) < 1e-20f) {
                if (Math.abs(ok) > h) return null;
                continue;
            }
            float t1 = (-h - ok) / dk, t2 = (h - ok) / dk;
            float enter = Math.min(t1, t2), exit = Math.max(t1, t2);
            if (enter > near) {
                near = enter;
                axis = k;
                sign = dk > 0f ? -1f : 1f; // the face the ray comes in through faces back at it
            }
            far = Math.min(far, exit);
            if (near > far) return null;
        }
        if (inside || axis < 0 || near < 0f) return null;
        return new Result(near, switch (axis) {
            case 0 -> new Vec3(sign, 0, 0);
            case 1 -> new Vec3(0, sign, 0);
            default -> new Vec3(0, 0, sign);
        });
    }

    private static Result capsule(Vec3 o, Vec3 d, float r, float h) {
        Vec3 nearest = Narrowphase.closestOnSegment(new Vec3(0, -h, 0), new Vec3(0, h, 0), o);
        if (nearest.sub(o).lengthSquared() < r * r) return null;
        // The capsule is a cylinder and two balls; the ray enters it where it first enters any of them.
        Result best = null;
        float a = d.x() * d.x() + d.z() * d.z();
        if (a > 1e-20f) {
            float b = o.x() * d.x() + o.z() * d.z(), c = o.x() * o.x() + o.z() * o.z() - r * r;
            float disc = b * b - a * c;
            if (disc >= 0f) {
                float t = (-b - (float) Math.sqrt(disc)) / a;
                float y = o.y() + d.y() * t;
                if (t >= 0f && Math.abs(y) <= h) best = new Result(t, new Vec3(o.x() + d.x() * t, 0, o.z() + d.z() * t).mul(1f / r));
            }
        }
        for (float end : new float[]{-h, h}) {
            Result hit = sphere(o, d, new Vec3(0, end, 0), r);
            if (hit != null && (best == null || hit.distance < best.distance)) best = hit;
        }
        return best;
    }

    private static Result plane(Vec3 o, Vec3 d, Vec3 n) {
        float facing = d.dot(n);
        if (facing >= 0f) return null; // parallel, or coming from behind
        float t = -o.dot(n) / facing;
        return t >= 0f ? new Result(t, n) : null;
    }
}
