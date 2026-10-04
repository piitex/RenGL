package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * Where a ray met a body: the shape it hit, the point and the surface normal there (facing back along the ray), and
 * how far along the ray, in units of the ray direction's length.
 */
public record RaycastHit(RigidBody body, BodyShape shape, Vec3 point, Vec3 normal, float distance) {
}
