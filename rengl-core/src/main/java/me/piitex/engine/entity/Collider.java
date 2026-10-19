package me.piitex.engine.entity;

import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Shape;
import me.piitex.engine.property.Property;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.List;

/**
 * A hit area on an entity, following it as it moves, turns and scales. Colliders only answer questions (does this
 * overlap that?); they don't push anything. What a hit means is up to the game, using the {@link Shape} queries.
 * <pre>{@code
 * // in a component on the player:
 * for (Collider other : getComponent(Collider.class).getOverlapping()) {
 *     if (other.getEntity().getComponent(Coin.class) != null) other.getEntity().destroy();
 * }
 * }</pre>
 * Turn on {@link EntityScene#setShowColliders} to see them.
 */
public abstract class Collider extends Component implements Bounded {
    @Property(tooltip = "How far the shape's center is from the entity, in the entity's own space")
    public float offsetX;
    @Property(tooltip = "How far the shape's center is from the entity, in the entity's own space")
    public float offsetY;

    /**
     * The area in world coordinates, where the entity is now.
     *
     * @throws IllegalStateException if the collider is not on an entity
     */
    public abstract Shape getShape();

    /**
     * The upright rectangle around {@link #getShape()}, or an empty one when the collider is not on an entity.
     */
    @Override
    public Rect getBounds() {
        return getEntity() == null ? new Rect(0, 0, 0, 0) : getShape().bounds();
    }

    /**
     * Whether this collider and {@code other} overlap by some area.
     */
    public boolean overlaps(Collider other) {
        return other != this && getShape().intersects(other.getShape());
    }

    /**
     * Every other collider in the scene, on an active entity, that overlaps this one. It tests them all, which is fine
     * for a few hundred; for more, keep them in a {@link me.piitex.engine.math.SpatialGrid}.
     */
    public List<Collider> getOverlapping() {
        List<Collider> found = new ArrayList<>();
        EntityScene scene = getScene();
        if (scene == null) return found;
        Shape shape = getShape();
        for (Collider other : scene.getComponents(Collider.class)) {
            if (other == this || !other.getEntity().isActiveInTree()) continue;
            if (shape.intersects(other.getShape())) found.add(other);
        }
        return found;
    }

    Entity requireEntity() {
        Entity entity = getEntity();
        if (entity == null) throw new IllegalStateException(getClass().getSimpleName() + " is not on an entity");
        return entity;
    }
}
