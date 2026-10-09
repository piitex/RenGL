package me.piitex.engine.entity;

import me.piitex.engine.math.Polygon;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Shape;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.property.Property;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A rectangle centered on its entity (plus the offset). It is a {@link Rect} while the entity is upright and a
 * {@link Polygon} while it is turned.
 */
public class BoxCollider extends Collider {
    @Property(min = 0, tooltip = "How wide the box is, in the entity's own units") public float width = 32f;
    @Property(min = 0, tooltip = "How tall the box is, in the entity's own units") public float height = 32f;

    public BoxCollider() {
    }

    public BoxCollider(float width, float height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public Shape getShape() {
        Entity entity = requireEntity();
        Vec2 center = entity.localToWorld(offsetX, offsetY);
        float w = width * Math.abs(entity.getWorldScaleX());
        float h = height * Math.abs(entity.getWorldScaleY());
        float rotation = entity.getWorldRotation() % 360f;
        if (rotation == 0f) return new Rect(center.x() - w / 2f, center.y() - h / 2f, w, h);
        return Polygon.box(center.x(), center.y(), w, h, rotation);
    }
}
