package me.piitex.engine.graphics;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.math.Rect;
import me.piitex.engine.scheduler.Lerp;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.Pool;

import java.util.Arrays;
import java.util.Random;

/**
 * Sparks, smoke, dust, debris: many short-lived pictures that fly, fade and disappear. The system moves them, reuses
 * them once they die, and draws them all through a {@link SpriteBatch}, so thousands at a time are cheap.
 * <pre>{@code
 * private final ParticleSystem particles = new ParticleSystem(5000);
 *
 * protected void fixedUpdate(float step) {
 *     if (enemy.died) particles.burst(EXPLOSION, enemy.x, enemy.y, 40);           // a ParticleEffect recipe
 *     particles.emit(smoke, rocket.x, rocket.y).velocity(0, -30).size(6f, 24f); // or one particle, set up by hand
 *     particles.update(step);
 * }
 *
 * protected void render(Renderer renderer, float alpha) {
 *     camera.begin(renderer);
 *     particles.draw(renderer, camera.getVisibleBounds());
 *     camera.end(renderer);
 * }
 * }</pre>
 * Particles are in whatever units they are drawn in: world units under a {@link Camera}, window points without one.
 * They keep the order they were emitted in, so newer particles draw over older ones on the same layer.
 * <p>
 * <b>Limit.</b> At most {@code maxParticles} are alive at once. Beyond that, {@link #emit} hands back a particle that
 * is never drawn, so its setters can still be chained, and the effect is simply thinner until some die.
 * <p>
 * <b>Mixing with other sprites.</b> {@link #addTo(SpriteBatch)} adds the particles to a batch of your own, drawn on
 * their {@link Particle#layer layers} among your sprites. {@link #addTo(SpriteBatch, int)} adds one layer, for
 * particles drawn under and over things that are not sprites.
 * <p>
 * Not thread-safe. Use it on the render thread, in scene hooks.
 */
public final class ParticleSystem {
    private final int maxParticles;
    private final Random random;
    private final Pool<Particle> pool;
    private Particle[] alive = new Particle[64];
    private int count;
    // Handed out when the system is full: set up like any other, but never added, moved or drawn.
    private final Particle discarded = new Particle();
    private final Sprite sprite = new Sprite();
    private SpriteBatch ownBatch;

    /**
     * A system that keeps at most {@code maxParticles} alive at once, with its own random numbers.
     */
    public ParticleSystem(int maxParticles) {
        this(maxParticles, new Random());
    }

    /**
     * A system that keeps at most {@code maxParticles} alive at once and picks the random values of its
     * {@link ParticleEffect bursts} with {@code random}, such as a game's seeded one.
     */
    public ParticleSystem(int maxParticles, Random random) {
        if (maxParticles < 0) throw new IllegalArgumentException("maxParticles must not be negative: " + maxParticles);
        this.maxParticles = maxParticles;
        this.random = random;
        this.pool = new Pool<>(Particle::new, null, maxParticles);
        sprite.setPivot(0.5f, 0.5f); // particles are placed and turned by their middle
    }

    /**
     * A new particle of {@code region} at ({@code x}, {@code y}), still and with every other value at its default.
     * Chain its setters to set it up. When the system is full, the particle returned is never drawn.
     */
    public Particle emit(TextureRegion region, float x, float y) {
        if (count >= maxParticles) {
            discarded.reset(region, x, y);
            return discarded;
        }
        Particle particle = pool.obtain();
        particle.reset(region, x, y);
        if (count == alive.length) alive = Arrays.copyOf(alive, Math.min(maxParticles, count * 2));
        alive[count++] = particle;
        return particle;
    }

    /**
     * {@code count} particles of {@code effect} flying out of ({@code x}, {@code y}) in every direction.
     */
    public void burst(ParticleEffect effect, float x, float y, int count) {
        burst(effect, x, y, count, null);
    }

    /**
     * {@link #burst(ParticleEffect, float, float, int)} in {@code color}, instead of the effect's own.
     */
    public void burst(ParticleEffect effect, float x, float y, int count, Color color) {
        for (int i = 0; i < count; i++) {
            effect.emit(this, random, x, y, random.nextDouble() * Math.PI * 2, color);
        }
    }

    /**
     * {@code count} particles of {@code effect} thrown out of ({@code x}, {@code y}) in a cone: around
     * {@code directionDegrees} (0 is to the right, 90 down), {@code spreadDegrees} wide in all.
     */
    public void cone(ParticleEffect effect, float x, float y, int count, float directionDegrees, float spreadDegrees) {
        cone(effect, x, y, count, directionDegrees, spreadDegrees, null);
    }

    /**
     * {@link #cone(ParticleEffect, float, float, int, float, float)} in {@code color}, instead of the effect's own.
     */
    public void cone(ParticleEffect effect, float x, float y, int count, float directionDegrees, float spreadDegrees,
                     Color color) {
        double direction = Math.toRadians(directionDegrees), spread = Math.toRadians(spreadDegrees);
        for (int i = 0; i < count; i++) {
            effect.emit(this, random, x, y, direction + (random.nextDouble() - 0.5) * spread, color);
        }
    }

    /**
     * Moves every particle on by {@code delta} seconds, and reuses those whose life is over.
     */
    public void update(float delta) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            Particle particle = alive[i];
            particle.age += delta;
            if (particle.age >= particle.lifetime) {
                pool.free(particle);
                continue;
            }
            if (delta > 0f) {
                particle.velocityX += particle.gravityX * delta;
                particle.velocityY += particle.gravityY * delta;
                if (particle.drag > 0f) {
                    float speedKept = (float) Math.pow(1f - particle.drag, delta);
                    particle.velocityX *= speedKept;
                    particle.velocityY *= speedKept;
                }
                particle.x += particle.velocityX * delta;
                particle.y += particle.velocityY * delta;
                particle.rotation += particle.spin * delta;
            }
            alive[kept++] = particle;
        }
        Arrays.fill(alive, kept, count, null);
        count = kept;
    }

    /**
     * Adds every particle to {@code batch}, each on its own layer, to be drawn with the batch's other sprites.
     */
    public void addTo(SpriteBatch batch) {
        for (int i = 0; i < count; i++) add(batch, alive[i]);
    }

    /**
     * Adds the particles on {@code layer} to {@code batch}, leaving the rest out.
     */
    public void addTo(SpriteBatch batch, int layer) {
        for (int i = 0; i < count; i++) {
            if (alive[i].layer == layer) add(batch, alive[i]);
        }
    }

    /**
     * Draws every particle now.
     */
    public void draw(Renderer renderer) {
        draw(renderer, null);
    }

    /**
     * Draws the particles that overlap {@code visible}, such as {@link Camera#getVisibleBounds()}. Null draws them all.
     */
    public void draw(Renderer renderer, Rect visible) {
        if (ownBatch == null) ownBatch = new SpriteBatch();
        addTo(ownBatch);
        ownBatch.draw(renderer, visible);
    }

    private void add(SpriteBatch batch, Particle particle) {
        TextureRegion region = particle.region;
        if (region == null || region.getWidth() <= 0) return;
        float progress = particle.getProgress();
        float size = particle.startSize + (particle.endSize - particle.startSize) * progress;
        float scale = size / region.getWidth();
        sprite.setRegion(region);
        sprite.setPosition(particle.x, particle.y);
        if (particle.alignToVelocity) {
            float speed = (float) Math.hypot(particle.velocityX, particle.velocityY);
            sprite.setRotation((float) Math.toDegrees(Math.atan2(particle.velocityY, particle.velocityX)));
            sprite.setScale(scale * (1f + speed * particle.stretch), scale);
        } else {
            sprite.setRotation(particle.rotation);
            sprite.setScale(scale);
        }
        sprite.setColor(particle.endColor == null ? particle.color : Lerp.COLOR.lerp(particle.color, particle.endColor, progress));
        sprite.setOpacity(particle.fade == null ? 1f : 1f - particle.fade.apply(progress));
        sprite.setLayer(particle.layer);
        batch.add(sprite);
    }

    /**
     * How many particles are alive.
     */
    public int getCount() {
        return count;
    }

    public int getMaxParticles() {
        return maxParticles;
    }

    /**
     * How many dead particles are kept to be reused, for a debug overlay.
     */
    public int getPooledCount() {
        return pool.getFreeCount();
    }

    /**
     * Removes every particle at once, such as when a level restarts.
     */
    public void clear() {
        for (int i = 0; i < count; i++) pool.free(alive[i]);
        Arrays.fill(alive, 0, count, null);
        count = 0;
    }
}
