package me.piitex.engine.graphics;

import me.piitex.engine.ui.color.Color;

/**
 * Emits particles of a {@link ParticleEffect} steadily, a number a second, as long as it is on: a campfire's sparks,
 * a rocket's smoke, a trail behind a fast projectile. A burst is a number at once ({@link ParticleSystem#burst}); this
 * is a rate over time.
 * <pre>{@code
 * private final ParticleEmitter smoke = new ParticleEmitter(particles, SMOKE).rate(40).cone(270, 30); // up, 30 degrees wide
 *
 * protected void fixedUpdate(float step) {
 *     smoke.moveTo(rocket.x, rocket.y); // follow the thing it trails from
 *     smoke.update(step);               // emits this step's share
 *     particles.update(step);
 * }
 * }</pre>
 * A fraction of a particle carries over to the next update, so 10 a second gives 10 in a second whatever the step is.
 * When the emitter has moved since the last update, the new particles are spread along the way it went, so a fast mover
 * leaves a smooth line rather than clumps. The first {@link #moveTo} (or {@link #setPosition}) does not leave a line from
 * wherever it was. Use {@link #setPosition} to jump somewhere without a trail between.
 * <p>
 * Not thread-safe. Use it on the render thread, in scene hooks, next to the system.
 */
public final class ParticleEmitter {
    private final ParticleSystem system;
    private ParticleEffect effect;
    private float rate = 20f;
    private float x, y, lastX, lastY;
    private boolean directed;
    private float directionDegrees, spreadDegrees;
    private Color color;
    private boolean on = true;
    private float carry;

    /**
     * An emitter that adds particles of {@code effect} to {@code system}, 20 a second in every direction, at (0, 0).
     */
    public ParticleEmitter(ParticleSystem system, ParticleEffect effect) {
        this.system = system;
        this.effect = effect;
    }

    /**
     * How many particles a second. 0 emits none, and a negative rate is 0.
     */
    public ParticleEmitter rate(float perSecond) {
        this.rate = Math.max(0f, perSecond);
        return this;
    }

    public float getRate() {
        return rate;
    }

    /**
     * Throws the particles in a cone around {@code directionDegrees} (0 is to the right, 90 down), {@code spreadDegrees}
     * wide in all, instead of in every direction.
     */
    public ParticleEmitter cone(float directionDegrees, float spreadDegrees) {
        this.directed = true;
        this.directionDegrees = directionDegrees;
        this.spreadDegrees = spreadDegrees;
        return this;
    }

    /**
     * Throws the particles in every direction, the default.
     */
    public ParticleEmitter everyDirection() {
        this.directed = false;
        return this;
    }

    /**
     * Emits in {@code color} instead of the effect's own, or null for the effect's own.
     */
    public ParticleEmitter color(Color color) {
        this.color = color;
        return this;
    }

    public ParticleEmitter effect(ParticleEffect effect) {
        this.effect = effect;
        return this;
    }

    /**
     * Moves the emitter, and the particles of the next update are spread along the way from where it was.
     */
    public ParticleEmitter moveTo(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    /**
     * Puts the emitter at (x, y) without a trail from where it was.
     */
    public ParticleEmitter setPosition(float x, float y) {
        this.x = this.lastX = x;
        this.y = this.lastY = y;
        return this;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    /**
     * Turns the emitter on or off. While off, {@link #update} emits nothing and the carried fraction is dropped.
     */
    public ParticleEmitter setOn(boolean on) {
        this.on = on;
        if (!on) carry = 0f;
        return this;
    }

    public boolean isOn() {
        return on;
    }

    /**
     * Emits what {@code delta} seconds at the rate come to. Call it before {@link ParticleSystem#update}, with the same
     * step. Returns how many particles it asked for (some are not drawn if the system is full).
     */
    public int update(float delta) {
        float fromX = lastX, fromY = lastY;
        lastX = x;
        lastY = y;
        if (!on || rate <= 0f || !(delta > 0f) || effect == null) return 0;
        carry += rate * delta;
        int count = (int) carry;
        carry -= count;
        for (int i = 1; i <= count; i++) {
            float t = i / (float) count; // the last one lands on the emitter, the others trail back along its path
            float px = fromX + (x - fromX) * t, py = fromY + (y - fromY) * t;
            if (directed) system.cone(effect, px, py, 1, directionDegrees, spreadDegrees, color);
            else system.burst(effect, px, py, 1, color);
        }
        return count;
    }
}
