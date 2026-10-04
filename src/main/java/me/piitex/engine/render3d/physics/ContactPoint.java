package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * One point where two bodies touch, in world space. {@code depth} is how far they overlap there, in metres; it is
 * slightly below 0 for shapes that are about to touch, which the world tracks a little early so fast bodies don't sink
 * in. {@code impulse} is how hard the bodies pushed apart there in the last step, in newton seconds.
 */
public record ContactPoint(Vec3 position, float depth, float impulse) {
}
