package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Where a ray hit a {@link Shape}: how far along the ray, the point on the outline, and the outline's outward
 * direction there as a vector of length 1. A ray that starts inside the shape hits at distance 0, at its origin, with
 * a {@link Vec2#ZERO zero} normal.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public record RayHit(float distance, Vec2 point, Vec2 normal) {
}
