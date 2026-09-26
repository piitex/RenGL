package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A point or a direction in 2D, such as a position converted by {@link me.piitex.engine.graphics.Camera}.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public record Vec2(float x, float y) {
    public static final Vec2 ZERO = new Vec2(0f, 0f);
}
