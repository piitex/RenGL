package me.piitex.engine.entity;

import me.piitex.engine.property.Property;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Where an {@link Entity} is, relative to its parent: position, rotation in degrees clockwise, and scale. An entity
 * with no parent is placed in the scene's world units. A child moves, turns and scales with its parent; its
 * {@link Entity#getWorldX() world} values say where it ends up.
 */
public final class Transform {
    @Property private float x;
    @Property private float y;
    @Property private float rotation;
    @Property private float scaleX = 1f;
    @Property private float scaleY = 1f;

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public void setX(float x) {
        this.x = x;
    }

    public void setY(float y) {
        this.y = y;
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void translate(float dx, float dy) {
        this.x += dx;
        this.y += dy;
    }

    /**
     * Degrees, clockwise.
     */
    public float getRotation() {
        return rotation;
    }

    public void setRotation(float degrees) {
        this.rotation = degrees;
    }

    public void rotate(float degrees) {
        this.rotation += degrees;
    }

    public float getScaleX() {
        return scaleX;
    }

    public float getScaleY() {
        return scaleY;
    }

    public void setScale(float scale) {
        this.scaleX = scale;
        this.scaleY = scale;
    }

    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    /**
     * Copies another transform's values.
     */
    public void set(Transform other) {
        this.x = other.x;
        this.y = other.y;
        this.rotation = other.rotation;
        this.scaleX = other.scaleX;
        this.scaleY = other.scaleY;
    }

    @Override
    public String toString() {
        return "Transform[x=" + x + ", y=" + y + ", rotation=" + rotation + ", scale=" + scaleX + "x" + scaleY + "]";
    }
}
