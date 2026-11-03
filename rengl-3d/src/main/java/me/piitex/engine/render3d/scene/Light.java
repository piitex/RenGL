package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;

/**
 * A light in a {@link Scene3D}: a directional light like the sun, a point light that fades out with distance, or a
 * spot light, a point light that only shines inside a cone. A scene tests up to {@link Scene3D#MAX_LIGHTS} lights at every pixel and sorts more of them (without shadows) into a grid, see {@link Scene3D.LightMode}.
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
    private float edgeCurve = 1f;
    private float coneAspect = 1f, coneSquareness = 0f, coneRoll = 0f;

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

    public float getEdgeCurve() {
        return edgeCurve;
    }

    /**
     * How a spot light's strength changes between the inner and the outer angle. 1, the default, is a straight ramp (in
     * the cosine of the angle). Above 1 the light drops away fast, leaving a tight bright middle and a long dim skirt;
     * below 1 it holds its strength until close to the outer angle and then falls, which makes the edge hard without
     * making the cone narrower. Between 0.1 and 8.
     */
    public Light setEdgeCurve(float curve) {
        this.edgeCurve = Math.max(0.1f, Math.min(8f, curve));
        return this;
    }

    /**
     * The height of a spot light's cone as a share of its width: 1, the default, is a round cone, below 1 it is a wide
     * slit and above 1 a tall one. The inner and outer angles are the half-angles across the width (along {@link #coneRight()}).
     */
    public float getConeAspect() {
        return coneAspect;
    }

    /**
     * 0, the default, for a round (or oval) cone up to 1 for a rectangular one, the cone of a window or a projector. Between
     * the two it is a rounded rectangle.
     */
    public float getConeSquareness() {
        return coneSquareness;
    }

    /**
     * How far a shaped cone is turned around its axis, in degrees, anticlockwise as seen from behind the light. 0 puts the
     * width across the horizon. It does nothing to a round cone.
     */
    public float getConeRoll() {
        return coneRoll;
    }

    /**
     * Shapes the cone of a spot light, see {@link #getConeAspect()}, {@link #getConeSquareness()} and {@link #getConeRoll()}.
     *
     * @param aspect      height over width, between 0.05 and 20
     * @param squareness  0 for round to 1 for rectangular
     * @param rollDegrees the turn around the axis, kept between -180 and 180
     */
    public Light setConeShape(float aspect, float squareness, float rollDegrees) {
        this.coneAspect = Math.max(0.05f, Math.min(20f, aspect));
        this.coneSquareness = Math.max(0f, Math.min(1f, squareness));
        this.coneRoll = (((rollDegrees % 360f) + 540f) % 360f) - 180f;
        return this;
    }

    /**
     * Whether the cone is anything but round: its aspect is not 1 or it is squared off.
     */
    public boolean isConeShaped() {
        return coneAspect != 1f || coneSquareness != 0f;
    }

    /**
     * The way across the width of a spot light's cone, a unit vector at right angles to {@link #getDirection()}. With no
     * roll it lies across the horizon, and for a light that points straight up or down along +X. The height runs along
     * {@code coneRight().cross(getDirection())}.
     */
    public Vec3 coneRight() {
        Vec3 d = direction;
        Vec3 ref = Math.abs(d.y()) > 0.99f ? Vec3.X : Vec3.UP;
        Vec3 right = d.cross(ref).normalized();
        if (coneRoll == 0f) return right;
        Vec3 up = right.cross(d);
        double a = Math.toRadians(coneRoll);
        return right.mul((float) Math.cos(a)).add(up.mul((float) Math.sin(a))).normalized();
    }

    /**
     * How much of a spot light's cone strength reaches a point that lies in the direction {@code toPoint} from the light
     * (a unit vector), from 0 outside the cone to 1 inside the inner angle. This is what the renderer's shaders work out
     * for every pixel, here so the editor and the tests can ask for the same number.
     */
    public float coneFactor(Vec3 toPoint) {
        float c = toPoint.dot(direction);
        if (c <= 0f) return 0f;
        float outerCos = (float) Math.cos(Math.toRadians(outerAngle)), innerCos = (float) Math.cos(Math.toRadians(innerAngle));
        if (isConeShaped()) {
            Vec3 right = coneRight();
            float x = toPoint.dot(right) / c, y = toPoint.dot(right.cross(direction)) / c / coneAspect;
            float round = (float) Math.sqrt(x * x + y * y), box = Math.max(Math.abs(x), Math.abs(y));
            float t = round + (box - round) * coneSquareness;
            c = (float) (1.0 / Math.sqrt(1.0 + t * t));
        }
        float s = Math.max(0f, Math.min(1f, (c - outerCos) / Math.max(innerCos - outerCos, 1e-4f)));
        return edgeCurve == 1f ? s : (float) Math.pow(s, edgeCurve);
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
