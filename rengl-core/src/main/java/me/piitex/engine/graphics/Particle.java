package me.piitex.engine.graphics;

import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.ui.color.Color;

/**
 * One particle of a {@link ParticleSystem}: a picture that flies, spins, grows or shrinks, changes color and fades
 * over a short life, then disappears. {@link ParticleSystem#emit} hands out a particle, and its setters chain:
 * <pre>{@code
 * particles.emit(spark, x, y).velocity(vx, vy).lifetime(0.4f).size(12f, 2f).color(Color.ORANGE).drag(0.9f);
 * }</pre>
 * Everything not set keeps its default: still, upright, white, the region's own size, living one second and fading
 * out evenly.
 * <p>
 * The system reuses a particle once it dies, so don't keep a reference to one after the frame it was emitted in.
 */
public final class Particle {
    TextureRegion region;
    float x, y;
    float velocityX, velocityY;
    float gravityX, gravityY;
    float drag;
    float age, lifetime;
    float startSize, endSize;
    float rotation, spin;
    Color color, endColor;
    Easing fade;
    boolean alignToVelocity;
    float stretch;
    int layer;

    Particle() {
    }

    // Back to the defaults, as a new particle of region at (x, y).
    void reset(TextureRegion region, float x, float y) {
        this.region = region;
        this.x = x;
        this.y = y;
        velocityX = velocityY = gravityX = gravityY = drag = 0f;
        age = 0f;
        lifetime = 1f;
        startSize = endSize = region == null ? 0f : region.getWidth();
        rotation = spin = 0f;
        color = Color.WHITE;
        endColor = null;
        fade = Easing.LINEAR;
        alignToVelocity = false;
        stretch = 0f;
        layer = 0;
    }

    /**
     * The picture it shows. Null shows nothing, though the particle still moves and lives out its life.
     */
    public Particle region(TextureRegion region) {
        this.region = region;
        return this;
    }

    public Particle position(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    /**
     * How fast it moves, in units a second.
     */
    public Particle velocity(float velocityX, float velocityY) {
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        return this;
    }

    /**
     * How fast its velocity changes, in units a second, every second: {@code gravity(0, 400)} makes it fall.
     */
    public Particle gravity(float gravityX, float gravityY) {
        this.gravityX = gravityX;
        this.gravityY = gravityY;
        return this;
    }

    /**
     * The share of its speed it loses each second, from 0 (none, the default) to 1 (it stops at once). 0.9 slows a
     * spark to a drift within a second.
     */
    public Particle drag(float drag) {
        this.drag = Math.clamp(drag, 0f, 1f);
        return this;
    }

    /**
     * How many seconds it lives, 1 by default. It disappears when that runs out.
     */
    public Particle lifetime(float seconds) {
        this.lifetime = seconds;
        return this;
    }

    /**
     * Its width in units, the whole of its life. The height follows the region's shape. The region's own width by
     * default.
     */
    public Particle size(float size) {
        return size(size, size);
    }

    /**
     * Its width in units when it appears and when it dies, changing evenly in between: {@code size(20, 2)} shrinks
     * away, {@code size(10, 200)} spreads like a shockwave.
     */
    public Particle size(float startSize, float endSize) {
        this.startSize = startSize;
        this.endSize = endSize;
        return this;
    }

    /**
     * How far it is turned, in degrees clockwise. Ignored while it is {@link #alignToVelocity aligned} to its velocity.
     */
    public Particle rotation(float degrees) {
        this.rotation = degrees;
        return this;
    }

    /**
     * How fast it turns, in degrees a second, clockwise. Negative turns the other way.
     */
    public Particle spin(float degreesPerSecond) {
        this.spin = degreesPerSecond;
        return this;
    }

    /**
     * The color the picture is multiplied with, the whole of its life. White, the default, leaves it as it is. Null
     * counts as white.
     */
    public Particle color(Color color) {
        this.color = color == null ? Color.WHITE : color;
        this.endColor = null;
        return this;
    }

    /**
     * The color it starts with and the one it ends with, blending evenly in between: fire from yellow to red.
     */
    public Particle color(Color startColor, Color endColor) {
        this.color = startColor == null ? Color.WHITE : startColor;
        this.endColor = endColor;
        return this;
    }

    /**
     * How it fades over its life. Its opacity is 1 minus {@code fade} applied to the share of its life gone, so
     * {@link Easing#LINEAR}, the default, fades evenly, {@link Easing#EASE_IN_CUBIC} stays bright for most of its
     * life and fades at the end, and null doesn't fade at all.
     */
    public Particle fade(Easing fade) {
        this.fade = fade;
        return this;
    }

    /**
     * Points the picture along the way it flies, taking its right-hand side as the front, and stretches it that way
     * the faster it goes: {@code 1 + speed * stretch} times its length. 0 aligns it without stretching. Suits sparks,
     * rain and streaks.
     */
    public Particle alignToVelocity(float stretch) {
        this.alignToVelocity = true;
        this.stretch = stretch;
        return this;
    }

    /**
     * Which layer it is drawn on, 0 by default. It becomes the {@link Sprite#setLayer layer} in a
     * {@link SpriteBatch}, and {@link ParticleSystem#addTo(SpriteBatch, int)} adds one layer at a time, for particles
     * drawn under and over other things.
     */
    public Particle layer(int layer) {
        this.layer = layer;
        return this;
    }

    /**
     * Ends its life now: it is gone at the next {@link ParticleSystem#update}.
     */
    public void kill() {
        age = Math.max(age, lifetime);
    }

    public TextureRegion getRegion() {
        return region;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getVelocityX() {
        return velocityX;
    }

    public float getVelocityY() {
        return velocityY;
    }

    /**
     * Seconds since it was emitted.
     */
    public float getAge() {
        return age;
    }

    public float getLifetime() {
        return lifetime;
    }

    /**
     * The share of its life gone, from 0 when it is emitted to 1 when it dies.
     */
    public float getProgress() {
        return lifetime > 0f ? Math.clamp(age / lifetime, 0f, 1f) : 1f;
    }

    public int getLayer() {
        return layer;
    }
}
