package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;

/**
 * A light in a {@link Scene3D}: either a directional light, like the sun, or a point light that fades out with
 * distance. A scene uses its first {@link Scene3D#MAX_LIGHTS} lights.
 */
public final class Light {
    public enum Type {DIRECTIONAL, POINT}

    private final Type type;
    private Vec3 vector;
    private Color color = Color.WHITE;
    private float intensity = 1f;
    private float range = 10f;

    private Light(Type type, Vec3 vector) {
        this.type = type;
        this.vector = vector;
    }

    /**
     * @param direction the way the light travels, so (0, -1, 0) shines straight down
     */
    public static Light directional(Vec3 direction, Color color, float intensity) {
        return new Light(Type.DIRECTIONAL, direction.normalized()).setColor(color).setIntensity(intensity);
    }

    /**
     * @param range the distance where the light has faded to nothing
     */
    public static Light point(Vec3 position, Color color, float intensity, float range) {
        return new Light(Type.POINT, position).setColor(color).setIntensity(intensity).setRange(range);
    }

    public Type getType() {
        return type;
    }

    /**
     * The direction of a directional light, or the position of a point light.
     */
    public Vec3 getVector() {
        return vector;
    }

    public Light setVector(Vec3 vector) {
        this.vector = type == Type.DIRECTIONAL ? vector.normalized() : vector;
        return this;
    }

    public Color getColor() {
        return color;
    }

    public Light setColor(Color color) {
        this.color = color;
        return this;
    }

    public float getIntensity() {
        return intensity;
    }

    public Light setIntensity(float intensity) {
        this.intensity = intensity;
        return this;
    }

    public float getRange() {
        return range;
    }

    public Light setRange(float range) {
        this.range = Math.max(0.001f, range);
        return this;
    }
}
