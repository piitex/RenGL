package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * Where a ray met a body: the shape it hit, the point and the surface normal there (facing back along the ray), how far
 * along the ray, in units of the ray direction's length, and for a {@link MeshShape} which triangle (-1 otherwise),
 * to tell what the ground is made of.
 */
public record RaycastHit(RigidBody body, BodyShape shape, Vec3 point, Vec3 normal, float distance, int triangle) {
    public RaycastHit(RigidBody body, BodyShape shape, Vec3 point, Vec3 normal, float distance) {
        this(body, shape, point, normal, distance, -1);
    }
}
