package me.piitex.engine.entity;

import me.piitex.engine.math.Rect;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A component that covers an area of the world, such as an image. The editor picks entities with the mouse by it and
 * outlines the selected one with it; an entity with no bounded component is picked by its position alone.
 * {@link SpriteRenderer} and the colliders are bounded.
 */
public interface Bounded {

    /**
     * The world area covered now, as the smallest upright rectangle around it. Empty when it covers nothing.
     */
    Rect getBounds();
}
