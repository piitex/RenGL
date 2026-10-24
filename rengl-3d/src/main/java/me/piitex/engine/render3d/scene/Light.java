package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;

/**
 * A light in a {@link Scene3D}: a directional light like the sun, a point light that fades out with distance, or a
 * spot light, a point light that only shines inside a cone. A scene shades up to {@link Scene3D#MAX_LIGHTS} lights at once, choosing the ones that matter when it has more.
 */
public final class Light {
    public enum Type {DIRECTIONAL, POINT, SPOT}

    private final Type type;
    private Vec3 vector;
    private Color color = Color.WHITE;
    private float intensity = 1f;
    private float range = 10f;
    private Shadows shadows;
    private LightFalloff falloff = LightFalloff.WINDOWED_INVERSE_SQUARE;
    private Vec3 direction = new Vec3(0, -1, 0);
    private float innerAngle = 25f, outerAngle = 35f;

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

    /**
     * A point light that shines only inside a cone.
     *
     * @param position   where the light is
     * @param direction  the way the cone points
     * @param range      the distance where the light has faded to nothing
     * @param innerDegrees the half-angle inside which the light is at full strength
     * @param outerDegrees the half-angle where it has faded to nothing, at most 85
     */
    public static Light spot(Vec3 position, Vec3 direction, Color color, float intensity, float range,
                             float innerDegrees, float outerDegrees) {
        Light light = new Light(Type.SPOT, position).setColor(color).setIntensity(intensity).setRange(range);
        light.direction = direction.normalized();
        return light.setCone(innerDegrees, outerDegrees);
    }

    public Type getType() {
        return type;
    }

    /**
     * The direction of a directional light, or the position of a point or spot light.
     */
    public Vec3 getVector() {
        return vector;
    }

    public Light setVector(Vec3 vector) {
        this.vector = type == Type.DIRECTIONAL ? vector.normalized() : vector;
        return this;
    }

    /**
     * The way a spot light points.
     */
    public Vec3 getDirection() {
        return direction;
    }

    public Light setDirection(Vec3 direction) {
        this.direction = direction.normalized();
        return this;
    }

    public float getInnerAngle() {
        return innerAngle;
    }

    public float getOuterAngle() {
        return outerAngle;
    }

    /**
     * The spot light's cone, as half-angles in degrees: full strength inside {@code inner}, fading to nothing at
     * {@code outer} (at most 85).
     */
    public Light setCone(float innerDegrees, float outerDegrees) {
        this.outerAngle = Math.max(1f, Math.min(85f, outerDegrees));
        this.innerAngle = Math.max(0f, Math.min(this.outerAngle - 0.5f, innerDegrees));
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

    public boolean isCastShadow() {
        return shadows != null;
    }

    /**
     * Makes the things in the scene cast shadows from this light, with everything taken from the scene
     * ({@link Scene3D#setShadowDistance}, {@link Scene3D#setShadowMapSize} and the other scene-wide shadow settings), or
     * stops it. Same as {@link #setShadows(Shadows) setShadows(Shadows.SCENE)} or {@code setShadows((Shadows) null)}. Any
     * kind of light can cast shadows; a scene draws those of up to {@link Scene3D#MAX_SHADOW_LIGHTS} lights.
     */
    public Light setCastShadow(boolean castShadow) {
        if (castShadow) {
            if (shadows == null) shadows = Shadows.SCENE;
        } else {
            shadows = null;
        }
        return this;
    }

    /**
     * The light's shadow settings, or null while it casts none.
     */
    public Shadows getShadows() {
        return shadows;
    }

    /**
     * Makes this light cast shadows as described, or none for null.
     */
    public Light setShadows(Shadows shadows) {
        this.shadows = shadows;
        return this;
    }

    /**
     * Makes this light cast shadows at a ready-made quality.
     */
    public Light setShadows(ShadowQuality quality) {
        return setShadows(Shadows.of(quality));
    }

    public LightFalloff getFalloff() {
        return falloff;
    }

    /**
     * How the light fades with distance ({@link LightFalloff#WINDOWED_INVERSE_SQUARE} by default). Directional lights don't fade.
     */
    public Light setFalloff(LightFalloff falloff) {
        this.falloff = falloff == null ? LightFalloff.WINDOWED_INVERSE_SQUARE : falloff;
        return this;
    }
}
