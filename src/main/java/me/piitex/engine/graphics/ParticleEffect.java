package me.piitex.engine.graphics;

import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.ui.color.Color;

import java.util.Random;

/**
 * A recipe for a kind of particle: its picture, and the range each of its values is picked from. Set one up once and
 * {@link ParticleSystem#burst burst} it wherever something happens:
 * <pre>{@code
 * private static final ParticleEffect SPARKS = new ParticleEffect(sparkRegion)
 *         .speed(200f, 600f).lifetime(0.3f, 0.6f).size(8f, 14f).endScale(0.2f)
 *         .drag(0.9f).alignToVelocity(1f / 400f).color(Color.ORANGE);
 *
 * particles.burst(SPARKS, x, y, 30);                    // every direction
 * particles.cone(SPARKS, x, y, 12, hitAngle, 60f);       // spraying back from a hit
 * particles.burst(SPARKS, x, y, 30, enemy.getColor());   // the same, in another color
 * }</pre>
 * Every particle gets its own random pick from each range. A range with the same number at both ends always gives
 * that number. Setters chain and can be called at any time; each burst uses the recipe as it is then.
 */
public final class ParticleEffect {
    private TextureRegion region;
    private float minSpeed, maxSpeed;
    private float minLifetime = 1f, maxLifetime = 1f;
    private float minSize = Float.NaN, maxSize = Float.NaN;
    private float endScale = 1f;
    private float drag;
    private float gravityX, gravityY;
    private float minRotation, maxRotation;
    private float minSpin, maxSpin;
    private Color color = Color.WHITE, endColor;
    private Easing fade = Easing.LINEAR;
    private boolean alignToVelocity;
    private float stretch;
    private int layer;

    /**
     * A recipe with no picture yet: set one with {@link #region}.
     */
    public ParticleEffect() {
    }

    public ParticleEffect(TextureRegion region) {
        this.region = region;
    }

    public ParticleEffect region(TextureRegion region) {
        this.region = region;
        return this;
    }

    /**
     * How fast each particle leaves, in units a second: 0 by default, so particles stay where they appear.
     */
    public ParticleEffect speed(float min, float max) {
        this.minSpeed = min;
        this.maxSpeed = max;
        return this;
    }

    /**
     * How many seconds each particle lives, 1 by default.
     */
    public ParticleEffect lifetime(float min, float max) {
        this.minLifetime = min;
        this.maxLifetime = max;
        return this;
    }

    /**
     * Each particle's width in units when it appears. The region's own width by default.
     */
    public ParticleEffect size(float min, float max) {
        this.minSize = min;
        this.maxSize = max;
        return this;
    }

    /**
     * Each particle's width when it dies, as a multiple of the width it started with: 1 by default, 0.2 to shrink
     * away, 4 to spread out.
     */
    public ParticleEffect endScale(float endScale) {
        this.endScale = endScale;
        return this;
    }

    /**
     * See {@link Particle#drag}.
     */
    public ParticleEffect drag(float drag) {
        this.drag = drag;
        return this;
    }

    /**
     * See {@link Particle#gravity}.
     */
    public ParticleEffect gravity(float gravityX, float gravityY) {
        this.gravityX = gravityX;
        this.gravityY = gravityY;
        return this;
    }

    /**
     * How far each particle starts turned, in degrees: 0 by default. {@code rotation(0, 360)} turns each at random.
     */
    public ParticleEffect rotation(float minDegrees, float maxDegrees) {
        this.minRotation = minDegrees;
        this.maxRotation = maxDegrees;
        return this;
    }

    /**
     * How fast each particle turns, in degrees a second: {@code spin(-360, 360)} tumbles them either way.
     */
    public ParticleEffect spin(float minDegreesPerSecond, float maxDegreesPerSecond) {
        this.minSpin = minDegreesPerSecond;
        this.maxSpin = maxDegreesPerSecond;
        return this;
    }

    /**
     * See {@link Particle#color(Color)}. A color passed to a burst replaces it.
     */
    public ParticleEffect color(Color color) {
        this.color = color == null ? Color.WHITE : color;
        this.endColor = null;
        return this;
    }

    /**
     * See {@link Particle#color(Color, Color)}. A color passed to a burst replaces the start color only.
     */
    public ParticleEffect color(Color startColor, Color endColor) {
        this.color = startColor == null ? Color.WHITE : startColor;
        this.endColor = endColor;
        return this;
    }

    /**
     * See {@link Particle#fade}.
     */
    public ParticleEffect fade(Easing fade) {
        this.fade = fade;
        return this;
    }

    /**
     * See {@link Particle#alignToVelocity}.
     */
    public ParticleEffect alignToVelocity(float stretch) {
        this.alignToVelocity = true;
        this.stretch = stretch;
        return this;
    }

    /**
     * Leaves particles turned by their {@link #rotation} instead of pointing them along the way they fly.
     */
    public ParticleEffect noAlign() {
        this.alignToVelocity = false;
        this.stretch = 0f;
        return this;
    }

    /**
     * See {@link Particle#layer}.
     */
    public ParticleEffect layer(int layer) {
        this.layer = layer;
        return this;
    }

    public TextureRegion getRegion() {
        return region;
    }

    // One particle flying at angleRadians, set up from the recipe; color replaces the recipe's start color if not null.
    void emit(ParticleSystem system, Random random, float x, float y, double angleRadians, Color color) {
        float speed = pick(random, minSpeed, maxSpeed);
        float lifetime = pick(random, minLifetime, maxLifetime);
        float size = Float.isNaN(minSize) ? (region == null ? 0f : region.getWidth()) : pick(random, minSize, maxSize);
        Particle particle = system.emit(region, x, y)
                .velocity((float) Math.cos(angleRadians) * speed, (float) Math.sin(angleRadians) * speed)
                .lifetime(lifetime)
                .size(size, size * endScale)
                .rotation(pick(random, minRotation, maxRotation))
                .spin(pick(random, minSpin, maxSpin))
                .drag(drag)
                .gravity(gravityX, gravityY)
                .color(color == null ? this.color : color, endColor)
                .fade(fade)
                .layer(layer);
        if (alignToVelocity) particle.alignToVelocity(stretch);
    }

    private static float pick(Random random, float min, float max) {
        return min == max ? min : min + random.nextFloat() * (max - min);
    }
}
