package me.piitex.engine.ui.background;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.AnimatedPaint;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A field of drifting dots that draw a fading line between any two of them closer
 * than {@link #getLinkDistance()}. This is the canvas particle-network effect made
 * familiar by particles.js and similar libraries, built on this engine's own
 * {@link Renderer#drawCircle} and {@link Renderer#drawLine}. See {@link Background}
 * and {@link me.piitex.engine.ui.Element#setBackground} for how to attach one of these
 * to something.
 * <p>
 * Particles bounce off whatever bounds they are given each call rather than wrapping,
 * and drawing is clipped to those bounds. Both are read live from {@link #render} and
 * {@link #update}'s own parameters every call, so resizing or moving the owning Element
 * between frames needs no extra step. Particles are seeded lazily, on the first
 * {@link #update} call, once real bounds are known. For the same reason,
 * {@link #setParticleCount} and {@link #setSpeedRange} invalidate the field rather than
 * reseeding it in place.
 * <p>
 * Cost is O(n^2) per frame, since every particle is checked against every other for a
 * possible link and there is no way to know which pairs are close without checking.
 * That is comfortable at the default {@link #getParticleCount()} of 80 and up into a
 * few hundred. Thousands of particles would need spatial partitioning, such as a grid
 * of buckets, which this class does not implement.
 * <p>
 * Particles can also be pushed away from the mouse cursor, which is off by default (see
 * {@link #setMouseInteractionEnabled}), and away from specific Containers such as a
 * draggable panel sitting on top of whatever this is attached to (see
 * {@link #addObstacle}).
 * <p>
 * This extends {@link AbstractBackground} for its own independent enable state,
 * {@link #fadeIn}, and {@link #fadeOut}, so {@code constellation.fadeOut(1.5f)} works
 * directly on this object with no Overlay Element involved.
 */
public class ConstellationBackground extends AbstractBackground {
    private static final Random RANDOM = new Random();

    private int particleCount = 80;
    private float minSpeed = 10f;
    private float maxSpeed = 40f;
    private float particleRadius = 2f;
    private float linkDistance = 120f;
    private float lineThickness = 1f;
    private Paint particleColor = new Color(1f, 1f, 1f, 0.8f);
    private Paint lineColor = new Color(1f, 1f, 1f, 0.35f);

    private boolean mouseInteractionEnabled = false;
    private float repelRadius = 120f;
    private float repelStrength = 600f;
    private float obstacleRepelRadius = 16f;
    private float obstacleRepelStrength = 600f;
    private final List<Container> obstacles = new ArrayList<>();

    private Particle[] particles;

    /**
     * Number of particles in the field. This invalidates the current field, which
     * reseeds fresh on the next {@link #update}. Particles have no identity worth
     * preserving across a count change, so adding or removing only the difference would
     * gain nothing.
     */
    public int getParticleCount() {
        return particleCount;
    }

    public void setParticleCount(int particleCount) {
        this.particleCount = Math.max(0, particleCount);
        particles = null;
    }

    /**
     * The random per-particle drift speed range, in points/second. Like {@link
     * #setParticleCount}, this invalidates the field. Speed is baked in at seed time
     * rather than read live, so there is nothing to update in place.
     */
    public float getMinSpeed() {
        return minSpeed;
    }

    public float getMaxSpeed() {
        return maxSpeed;
    }

    public void setSpeedRange(float minSpeed, float maxSpeed) {
        this.minSpeed = Math.max(0f, minSpeed);
        this.maxSpeed = Math.max(this.minSpeed, maxSpeed);
        particles = null;
    }

    /**
     * Base radius, in points, that particles are drawn at. Each particle scales this by
     * its own fixed random factor, picked once at seed time, so the field has some size
     * variety instead of every dot being identical. This takes effect immediately and
     * needs no reseed, unlike {@link #setParticleCount} and {@link #setSpeedRange}.
     */
    public float getParticleRadius() {
        return particleRadius;
    }

    public void setParticleRadius(float particleRadius) {
        this.particleRadius = Math.max(0f, particleRadius);
    }

    /**
     * Maximum distance, in points, between two particles for a line to be drawn
     * between them. The line fades out as the pair approaches this distance, and is
     * fully opaque, at {@link #getLineColor()}'s own alpha, when they overlap.
     */
    public float getLinkDistance() {
        return linkDistance;
    }

    public void setLinkDistance(float linkDistance) {
        this.linkDistance = Math.max(0f, linkDistance);
    }

    public float getLineThickness() {
        return lineThickness;
    }

    public void setLineThickness(float lineThickness) {
        this.lineThickness = Math.max(0f, lineThickness);
    }

    /**
     * The particle dot color. A {@link me.piitex.engine.ui.color.RainbowColor} cycles the
     * whole field through the colour wheel. Pair it with a matching RainbowColor for
     * {@link #setLineColor}, same cycle duration and a lower alpha, and the two stay in sync.
     */
    public Paint getParticleColor() {
        return particleColor;
    }

    public void setParticleColor(Paint particleColor) {
        this.particleColor = particleColor;
    }

    /**
     * The line color's own alpha is the line's opacity at zero distance. See {@link #getLinkDistance()} for how it fades from there.
     * A {@link me.piitex.engine.ui.color.RainbowColor} works here too; see {@link #getParticleColor()}.
     */
    public Paint getLineColor() {
        return lineColor;
    }

    public void setLineColor(Paint lineColor) {
        this.lineColor = lineColor;
    }

    /**
     * Whether particles are pushed away from the mouse cursor. Off by default, since
     * most uses of this effect are an ambient background with nothing else reading live
     * mouse position. The position comes from {@link MouseTracker}, the same mechanism
     * {@link me.piitex.engine.input.CursorManager} uses for the OS cursor shape. It is
     * read in absolute window coordinates and compared against the bounds passed into
     * {@link #update} each call, so this works regardless of which Element the effect is
     * attached to or where that Element sits. The mouse does not need to be hovering
     * that Element, or anything on top of it, only to be within
     * {@link #getRepelRadius()} points of a particle.
     */
    public boolean isMouseInteractionEnabled() {
        return mouseInteractionEnabled;
    }

    public void setMouseInteractionEnabled(boolean mouseInteractionEnabled) {
        this.mouseInteractionEnabled = mouseInteractionEnabled;
    }

    /**
     * How close the mouse has to be to a particle, in points, before it starts pushing it. The push strengthens smoothly from zero at this distance up to {@link #getRepelStrength()} at zero distance. This only applies when {@link #isMouseInteractionEnabled()}.
     */
    public float getRepelRadius() {
        return repelRadius;
    }

    public void setRepelRadius(float repelRadius) {
        this.repelRadius = Math.max(0f, repelRadius);
    }

    /**
     * How hard the mouse pushes a particle at zero distance, in points per second. This is the same unit as {@link #setSpeedRange}, so the two can be compared to judge how forceful a push is relative to the field's ambient drift. This only applies when {@link #isMouseInteractionEnabled()}.
     */
    public float getRepelStrength() {
        return repelStrength;
    }

    public void setRepelStrength(float repelStrength) {
        this.repelStrength = Math.max(0f, repelStrength);
    }

    /**
     * Registers {@code obstacle} as something particles are pushed away from. The
     * falloff shape matches the mouse push, but it uses its own
     * {@link #getObstacleRepelRadius()} and {@link #getObstacleRepelStrength()}, which
     * default much tighter: a wide radius makes particles huddle at a fixed distance
     * from the box rather than being nudged out of the way at its edge. The push is
     * sourced from {@code obstacle}'s live box, read fresh every frame in whatever
     * position it currently occupies, rather than from a fixed point. This is intended
     * for a {@link Container#setDraggable} panel sitting on top of whatever the effect
     * is attached to, so the field reacts as the panel is dragged around, but
     * {@code obstacle} does not have to be draggable; a stationary Container behaves the
     * same way.
     * <p>
     * This mirrors {@link Container#addCollideable}'s API: it is a no-op for
     * {@code null} or an already-registered Container, and an empty obstacle list, the
     * default, never pushes anything. There is no separate on/off flag.
     */
    public void addObstacle(Container obstacle) {
        if (obstacle != null && !obstacles.contains(obstacle)) {
            obstacles.add(obstacle);
        }
    }

    /**
     * Unregisters {@code obstacle}. See {@link #addObstacle}. A no-op if it was not registered.
     */
    public void removeObstacle(Container obstacle) {
        obstacles.remove(obstacle);
    }

    /**
     * Un-registers every Container added via {@link #addObstacle}.
     */
    public void clearObstacles() {
        obstacles.clear();
    }

    /**
     * The Containers particles are currently pushed away from. See {@link #addObstacle}. This is the live list, not a copy.
     */
    public List<Container> getObstacles() {
        return obstacles;
    }

    /**
     * As {@link #getRepelRadius()}, but for the push away from a registered {@link #addObstacle}'s edge rather than the mouse. It defaults much tighter, at 16pt, so particles are nudged at the box's boundary instead of held off at a wide gap.
     */
    public float getObstacleRepelRadius() {
        return obstacleRepelRadius;
    }

    public void setObstacleRepelRadius(float obstacleRepelRadius) {
        this.obstacleRepelRadius = Math.max(0f, obstacleRepelRadius);
    }

    /**
     * As {@link #getRepelStrength()}, but for {@link #addObstacle} pushes.
     */
    public float getObstacleRepelStrength() {
        return obstacleRepelStrength;
    }

    public void setObstacleRepelStrength(float obstacleRepelStrength) {
        this.obstacleRepelStrength = Math.max(0f, obstacleRepelStrength);
    }

    private void seedParticles(float width, float height) {
        float w = Math.max(1f, width);
        float h = Math.max(1f, height);

        particles = new Particle[particleCount];
        for (int i = 0; i < particleCount; i++) {
            Particle p = new Particle();
            p.x = RANDOM.nextFloat() * w;
            p.y = RANDOM.nextFloat() * h;

            float angle = RANDOM.nextFloat() * (float) (Math.PI * 2);
            float speed = minSpeed + RANDOM.nextFloat() * (maxSpeed - minSpeed);
            p.vx = (float) Math.cos(angle) * speed;
            p.vy = (float) Math.sin(angle) * speed;

            p.radiusScale = 0.6f + RANDOM.nextFloat() * 0.8f;
            particles[i] = p;
        }
    }

    @Override
    protected void updateEffect(float delta, float x, float y, float width, float height) {
        AnimatedPaint.advanceIfAnimated(particleColor, delta);
        AnimatedPaint.advanceIfAnimated(lineColor, delta);
        if (particles == null) seedParticles(width, height);

        // Mouse position is fetched once per frame, in local space, rather than per
        // particle. It does not change mid-loop, and fetching it here lets a mouse
        // outside the window (MouseTracker.isInWindow() false) disable the push below
        // rather than pushing from a stale last-known point.
        boolean repel = mouseInteractionEnabled && repelRadius > 0f && MouseTracker.isInWindow();
        float mouseX = repel ? (float) (MouseTracker.getX() - x) : 0f;
        float mouseY = repel ? (float) (MouseTracker.getY() - y) : 0f;
        float repelRadiusSq = repelRadius * repelRadius;

        for (Particle p : particles) {
            p.x += p.vx * delta;
            p.y += p.vy * delta;

            if (repel) {
                float dx = p.x - mouseX;
                float dy = p.y - mouseY;
                float distSq = dx * dx + dy * dy;
                // The > 0 guard avoids a divide-by-zero direction for the (practically
                // impossible, but not worth crashing over) case of a particle sitting
                // exactly on the mouse position.
                if (distSq < repelRadiusSq && distSq > 0f) {
                    float dist = (float) Math.sqrt(distSq);
                    float push = (1f - dist / repelRadius) * repelStrength * delta;
                    p.x += (dx / dist) * push;
                    p.y += (dy / dist) * push;
                }
            }

            if (obstacleRepelRadius > 0f && !obstacles.isEmpty()) {
                for (Container obstacle : obstacles) {
                    pushFromObstacle(p, obstacle, x, y, delta);
                }
            }

            if (p.x < 0f || p.x > width) {
                p.vx = -p.vx;
                p.x = Math.max(0f, Math.min(p.x, width));
            }
            if (p.y < 0f || p.y > height) {
                p.vy = -p.vy;
                p.y = Math.max(0f, Math.min(p.y, height));
            }
        }
    }

    /**
     * Pushes {@code p} away from {@code obstacle}'s live box, in local space relative
     * to ({@code ownerX}, {@code ownerY}). The falloff matches the mouse push in
     * {@link #update}, but distance is measured to the nearest point on the box rather
     * than to a single point, so the push comes from whichever edge or corner is
     * closest. If {@code p} is already inside the box, where the nearest point is
     * {@code p} itself and the distance is zero either way, the push is away from the
     * box's center instead. Nearest-point distance cannot distinguish an edge touch from
     * a particle buried deep inside, while a center-relative push always has a
     * well-defined direction to escape along.
     */
    private void pushFromObstacle(Particle p, Container obstacle, float ownerX, float ownerY, float delta) {
        float ox = (float) (obstacle.getAbsoluteX() - ownerX);
        float oy = (float) (obstacle.getAbsoluteY() - ownerY);
        float ow = (float) obstacle.getWidth();
        float oh = (float) obstacle.getHeight();

        float nearestX = Math.max(ox, Math.min(p.x, ox + ow));
        float nearestY = Math.max(oy, Math.min(p.y, oy + oh));
        float dx = p.x - nearestX;
        float dy = p.y - nearestY;

        if (dx == 0f && dy == 0f) {
            dx = p.x - (ox + ow / 2f);
            dy = p.y - (oy + oh / 2f);
            if (dx == 0f && dy == 0f) dx = 1f; // exactly centered, so pick an arbitrary escape direction
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float push = obstacleRepelStrength * delta; // full strength anywhere inside the box
            p.x += (dx / dist) * push;
            p.y += (dy / dist) * push;
            return;
        }

        float distSq = dx * dx + dy * dy;
        if (distSq >= obstacleRepelRadius * obstacleRepelRadius) return;

        float dist = (float) Math.sqrt(distSq);
        float push = (1f - dist / obstacleRepelRadius) * obstacleRepelStrength * delta;
        p.x += (dx / dist) * push;
        p.y += (dy / dist) * push;
    }

    @Override
    protected void renderEffect(Renderer renderer, float x, float y, float width, float height) {
        if (particles == null) return; // no update() call has seeded the field yet this frame

        renderer.pushClip(x, y, width, height);

        // Lines first so the particle dots draw crisply on top of them, not the other way around.
        if (lineColor != null && linkDistance > 0f) {
            float maxDistSq = linkDistance * linkDistance;
            for (int i = 0; i < particles.length; i++) {
                Particle a = particles[i];
                for (int j = i + 1; j < particles.length; j++) {
                    Particle b = particles[j];
                    float dx = a.x - b.x;
                    float dy = a.y - b.y;
                    float distSq = dx * dx + dy * dy;
                    if (distSq >= maxDistSq) continue;

                    float proximity = 1f - (float) Math.sqrt(distSq) / linkDistance;
                    renderer.pushAlpha(proximity);
                    renderer.drawLine(x + a.x, y + a.y, x + b.x, y + b.y, lineColor, lineThickness);
                    renderer.popAlpha();
                }
            }
        }

        if (particleColor != null) {
            for (Particle p : particles) {
                renderer.drawCircle(x + p.x, y + p.y, particleRadius * p.radiusScale, particleColor);
            }
        }

        renderer.popClip();
    }

    /**
     * One drifting dot: position, velocity in points per second baked in at seed time, and a fixed per-particle size multiplier.
     */
    private static final class Particle {
        float x, y, vx, vy;
        float radiusScale;
    }
}
