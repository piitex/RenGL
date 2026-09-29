package me.piitex.engine.entity;

import me.piitex.engine.math.Circle;
import me.piitex.engine.math.Shape;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.property.Property;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A circle centered on its entity (plus the offset). Scaled unevenly, it takes the larger scale, since it stays a
 * circle.
 */
public class CircleCollider extends Collider {
    @Property(min = 0) public float radius = 16f;

    public CircleCollider() {
    }

    public CircleCollider(float radius) {
        this.radius = radius;
    }

    @Override
    public Shape getShape() {
        Entity entity = requireEntity();
        Vec2 center = entity.localToWorld(offsetX, offsetY);
        float scale = Math.max(Math.abs(entity.getWorldScaleX()), Math.abs(entity.getWorldScaleY()));
        return new Circle(center.x(), center.y(), radius * scale);
    }
}
