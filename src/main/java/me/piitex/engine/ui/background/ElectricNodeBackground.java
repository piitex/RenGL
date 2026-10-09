package me.piitex.engine.ui.background;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.AnimatedPaint;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One or more glowing energy orbs that bounce around the owning Element's bounds and
 * shoot out crackling arcs of lightning. See {@link Background} and
 * {@link me.piitex.engine.ui.Element#setBackground} for how to attach one.
 * <p>
 * Each node fires arcs at random intervals, averaging {@link #getArcRate()} per second.
 * An arc is a jagged bolt built by midpoint displacement, optionally forked into
 * thinner branches, that lives for about {@link #getArcLifetime()} seconds. While alive
 * it re-jitters its shape every few frames and flickers in brightness, which is what
 * makes it read as crackling electricity rather than a static zig-zag. Where an arc
 * aims depends on what is nearby:
 * <ul>
 *   <li>If {@link #isMouseInteractionEnabled()} and the cursor is within
 *   {@link #getStrikeRadius()} of the node, most arcs reach for the cursor.</li>
 *   <li>Otherwise, if another node is within {@link #getNodeLinkDistance()}, arcs often
 *   jump between the two.</li>
 *   <li>Otherwise the arc shoots off in a random direction. One long enough to reach the
 *   edge of the bounds strikes it, staying pinned there as the node moves, and throws a
 *   shower of sparks.</li>
 * </ul>
 * {@link #discharge()} fires a burst from every node at once, for reacting to an event.
 * <p>
 * The renderer has no blur, so glow is built from layered strokes: a wide faint pass, a
 * narrower brighter one, and a thin near-white core, all drawn with
 * {@link Renderer#drawPolyline} so the jagged joints stay clean. Nodes are stacked
 * translucent circles, giving a soft radial falloff. A dark base color on the element
 * shows it off best.
 * <p>
 * Like {@link ConstellationBackground}, everything is simulated in the owner's local
 * space from {@link #update}'s bounds, and nodes are seeded lazily on the first update
 * once real bounds are known. Cost is small and independent of element size: a handful
 * of polylines and circles per node per frame.
 */
public class ElectricNodeBackground extends AbstractBackground {
    private static final Random RANDOM = new Random();

    private static final int MAIN_DEPTH = 4; // 2^4 = 16 segments per main bolt
    private static final int BRANCH_DEPTH = 3; // 8 segments per branch
    private static final int MAX_ARCS = 96;
    private static final int MAX_SPARKS = 240;
    private static final float CRACKLE_INTERVAL = 0.045f; // seconds between re-jitters
    private static final int GLOW_LAYERS = 10;

    private int nodeCount = 1;
    private float nodeRadius = 9f;
    private float minSpeed = 60f;
    private float maxSpeed = 140f;
    private float arcRate = 5f;
    private float minArcLength = 70f;
    private float maxArcLength = 260f;
    private float arcLifetime = 0.28f;
    private float jaggedness = 1f;
    private float branchChance = 0.6f;
    private float boltThickness = 2f;
    private float nodeLinkDistance = 260f;
    private Paint glowColor = new Color(0.35f, 0.7f, 1f, 1f);
    private Paint coreColor = new Color(0.92f, 0.97f, 1f, 1f);
    private boolean sparksEnabled = true;
    private boolean mouseInteractionEnabled = false;
    private float strikeRadius = 220f;

    private Node[] nodes;
    private final List<Arc> arcs = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private float width;
    private float height;
    private boolean mouseActive;
    private float mouseX;
    private float mouseY;
    private int pendingDischarges;

    /**
     * Number of bouncing nodes. This invalidates the current nodes and any live arcs,
     * which reseed on the next {@link #update}.
     */
    public int getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(int nodeCount) {
        this.nodeCount = Math.max(0, nodeCount);
        nodes = null;
        arcs.clear();
    }

    /**
     * Radius, in points, of a node's bright core. Its glow extends to roughly four times this.
     */
    public float getNodeRadius() {
        return nodeRadius;
    }

    public void setNodeRadius(float nodeRadius) {
        this.nodeRadius = Math.max(1f, nodeRadius);
    }

    /**
     * The random per-node travel speed range, in points/second. Like
     * {@link #setNodeCount}, this invalidates the nodes, since speed is picked at seed time.
     */
    public void setSpeedRange(float minSpeed, float maxSpeed) {
        this.minSpeed = Math.max(0f, minSpeed);
        this.maxSpeed = Math.max(this.minSpeed, maxSpeed);
        nodes = null;
        arcs.clear();
    }

    public float getMinSpeed() {
        return minSpeed;
    }

    public float getMaxSpeed() {
        return maxSpeed;
    }

    /**
     * Average arcs fired per second, per node. The gaps between arcs are random around
     * this average rather than evenly spaced, so the discharges feel erratic. 0 stops
     * arcs entirely, leaving only the glowing nodes and {@link #discharge()}.
     */
    public float getArcRate() {
        return arcRate;
    }

    public void setArcRate(float arcRate) {
        this.arcRate = Math.max(0f, arcRate);
    }

    /**
     * The random length range, in points, of an arc shot in a free direction. An arc
     * whose length reaches the edge of the bounds strikes it instead of stopping short.
     */
    public void setArcLengthRange(float minArcLength, float maxArcLength) {
        this.minArcLength = Math.max(1f, minArcLength);
        this.maxArcLength = Math.max(this.minArcLength, maxArcLength);
    }

    public float getMinArcLength() {
        return minArcLength;
    }

    public float getMaxArcLength() {
        return maxArcLength;
    }

    /**
     * Average seconds an arc stays visible, each one varying by up to 40% either way.
     */
    public float getArcLifetime() {
        return arcLifetime;
    }

    public void setArcLifetime(float arcLifetime) {
        this.arcLifetime = Math.max(0.02f, arcLifetime);
    }

    /**
     * How far bolts zig-zag away from a straight line, clamped to [0, 3] (default 1). 0 draws straight beams.
     */
    public float getJaggedness() {
        return jaggedness;
    }

    public void setJaggedness(float jaggedness) {
        this.jaggedness = Math.max(0f, Math.min(3f, jaggedness));
    }

    /**
     * Chance, in [0, 1], that each arc forks. A forking arc gets one to three thinner branches.
     */
    public float getBranchChance() {
        return branchChance;
    }

    public void setBranchChance(float branchChance) {
        this.branchChance = Math.max(0f, Math.min(1f, branchChance));
    }

    /**
     * Width, in points, of a bolt's bright core. The glow around it scales with this.
     */
    public float getBoltThickness() {
        return boltThickness;
    }

    public void setBoltThickness(float boltThickness) {
        this.boltThickness = Math.max(0.5f, boltThickness);
    }

    /**
     * Two nodes closer than this, in points, arc between each other. 0 turns node-to-node arcs off.
     */
    public float getNodeLinkDistance() {
        return nodeLinkDistance;
    }

    public void setNodeLinkDistance(float nodeLinkDistance) {
        this.nodeLinkDistance = Math.max(0f, nodeLinkDistance);
    }

    /**
     * The outer glow color of bolts and nodes. Its alpha scales the whole glow. A
     * {@link me.piitex.engine.ui.color.RainbowColor} gives a glow that cycles through the
     * colour wheel.
     */
    public Paint getGlowColor() {
        return glowColor;
    }

    public void setGlowColor(Paint glowColor) {
        this.glowColor = glowColor;
    }

    /**
     * The color of the thin bright center of each bolt, node, and spark. Near-white reads as hottest.
     * With a {@link me.piitex.engine.ui.color.RainbowColor} glow, a low-saturation RainbowColor
     * here, such as {@code new RainbowColor(6f, 0.15f, 1f, 1f)}, keeps the core pale but tinted to match.
     */
    public Paint getCoreColor() {
        return coreColor;
    }

    public void setCoreColor(Paint coreColor) {
        this.coreColor = coreColor;
    }

    /**
     * Whether strikes against a wall, another node, or the cursor throw off sparks. On by default.
     */
    public boolean isSparksEnabled() {
        return sparksEnabled;
    }

    public void setSparksEnabled(boolean sparksEnabled) {
        this.sparksEnabled = sparksEnabled;
        if (!sparksEnabled) sparks.clear();
    }

    /**
     * Whether nodes arc toward the mouse cursor when it is within {@link #getStrikeRadius()}
     * of them. Off by default. As with {@link ConstellationBackground#setMouseInteractionEnabled},
     * the position comes from {@link MouseTracker} in window coordinates, so this works
     * wherever the owning Element sits. The cursor must be inside the owner's bounds.
     */
    public boolean isMouseInteractionEnabled() {
        return mouseInteractionEnabled;
    }

    public void setMouseInteractionEnabled(boolean mouseInteractionEnabled) {
        this.mouseInteractionEnabled = mouseInteractionEnabled;
    }

    /**
     * How close the cursor must be to a node, in points, for that node to arc toward it. Only applies when {@link #isMouseInteractionEnabled()}.
     */
    public float getStrikeRadius() {
        return strikeRadius;
    }

    public void setStrikeRadius(float strikeRadius) {
        this.strikeRadius = Math.max(0f, strikeRadius);
    }

    /**
     * Fires a burst of arcs from every node at once on the next {@link #update}, for
     * example when a button is pressed or something is hit. Calls made before the first
     * update are kept until the nodes exist.
     */
    public void discharge() {
        pendingDischarges++;
    }

    private void seedNodes() {
        nodes = new Node[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            Node n = new Node();
            n.x = nodeRadius + RANDOM.nextFloat() * Math.max(0f, width - nodeRadius * 2f);
            n.y = nodeRadius + RANDOM.nextFloat() * Math.max(0f, height - nodeRadius * 2f);

            float angle = RANDOM.nextFloat() * (float) (Math.PI * 2);
            float speed = minSpeed + RANDOM.nextFloat() * (maxSpeed - minSpeed);
            n.vx = (float) Math.cos(angle) * speed;
            n.vy = (float) Math.sin(angle) * speed;

            n.cooldown = nextArcDelay() * RANDOM.nextFloat(); // stagger so nodes don't fire in sync
            n.pulsePhase = RANDOM.nextFloat() * (float) (Math.PI * 2);
            nodes[i] = n;
        }
    }

    /**
     * Seconds until a node's next arc: exponentially distributed around 1 / {@link #arcRate},
     * which is what gives discharges their irregular, bursty rhythm.
     */
    private float nextArcDelay() {
        if (arcRate <= 0f) return Float.MAX_VALUE;
        return (float) -Math.log(1.0 - RANDOM.nextDouble()) / arcRate;
    }

    @Override
    protected void updateEffect(float delta, float x, float y, float width, float height) {
        this.width = width;
        this.height = height;
        AnimatedPaint.advanceIfAnimated(glowColor, delta);
        AnimatedPaint.advanceIfAnimated(coreColor, delta);
        if (nodes == null) seedNodes();

        mouseActive = false;
        if (mouseInteractionEnabled && MouseTracker.isInWindow()) {
            mouseX = (float) (MouseTracker.getX() - x);
            mouseY = (float) (MouseTracker.getY() - y);
            mouseActive = mouseX >= 0f && mouseX <= width && mouseY >= 0f && mouseY <= height;
        }

        for (Node n : nodes) {
            moveNode(n, delta);
        }

        int discharges = pendingDischarges;
        pendingDischarges = 0;
        for (Node n : nodes) {
            n.cooldown -= delta;
            while (n.cooldown <= 0f) {
                spawnArc(n);
                n.cooldown += nextArcDelay();
            }
            for (int i = 0; i < discharges * 6; i++) {
                spawnArc(n);
            }
        }

        for (int i = arcs.size() - 1; i >= 0; i--) {
            Arc arc = arcs.get(i);
            arc.age += delta;
            if (arc.age >= arc.life) {
                removeSwap(arcs, i);
                continue;
            }
            arc.crackleTimer -= delta;
            if (arc.crackleTimer <= 0f) {
                arc.crackleTimer += CRACKLE_INTERVAL;
                crackle(arc);
            }
            layoutArc(arc);
        }

        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.age += delta;
            if (s.age >= s.life) {
                removeSwap(sparks, i);
                continue;
            }
            float drag = (float) Math.exp(-5f * delta);
            s.vx *= drag;
            s.vy *= drag;
            s.x += s.vx * delta;
            s.y += s.vy * delta;
        }
    }

    private void moveNode(Node n, float delta) {
        n.x += n.vx * delta;
        n.y += n.vy * delta;
        n.flash = Math.max(0f, n.flash - delta * 5f);
        n.pulsePhase += delta * 3f;

        float minX = nodeRadius, maxX = Math.max(minX, width - nodeRadius);
        float minY = nodeRadius, maxY = Math.max(minY, height - nodeRadius);
        if (n.x < minX || n.x > maxX) {
            n.vx = -n.vx;
            n.x = Math.max(minX, Math.min(n.x, maxX));
        }
        if (n.y < minY || n.y > maxY) {
            n.vy = -n.vy;
            n.y = Math.max(minY, Math.min(n.y, maxY));
        }
    }

    private void spawnArc(Node from) {
        if (arcs.size() >= MAX_ARCS) return;

        Arc arc = new Arc();
        arc.from = from;
        arc.life = arcLifetime * (0.6f + RANDOM.nextFloat() * 0.8f);
        arc.crackleTimer = CRACKLE_INTERVAL;

        Node linked = nearestNode(from);
        if (mouseActive && dist(from.x, from.y, mouseX, mouseY) < strikeRadius && RANDOM.nextFloat() < 0.75f) {
            arc.toMouse = true;
        } else if (linked != null && RANDOM.nextFloat() < 0.5f) {
            arc.to = linked;
            linked.flash = 1f;
        } else {
            arc.angle = RANDOM.nextFloat() * (float) (Math.PI * 2);
            arc.length = minArcLength + RANDOM.nextFloat() * (maxArcLength - minArcLength);
            arc.toWall = arc.length >= distanceToWall(from.x, from.y, arc.angle);
        }

        arc.offsets = new float[(1 << MAIN_DEPTH) + 1];
        if (RANDOM.nextFloat() < branchChance) {
            int count = 1 + RANDOM.nextInt(3);
            arc.branches = new Branch[count];
            for (int i = 0; i < count; i++) {
                Branch b = new Branch();
                b.offsets = new float[(1 << BRANCH_DEPTH) + 1];
                arc.branches[i] = b;
            }
        } else {
            arc.branches = new Branch[0];
        }
        crackle(arc);
        layoutArc(arc);

        from.flash = 1f;
        arcs.add(arc);

        if (sparksEnabled && (arc.toWall || arc.toMouse || arc.to != null)) {
            emitSparks(arc.endX, arc.endY, arc.dirAngle + (float) Math.PI, 5 + RANDOM.nextInt(6));
        }
    }

    private Node nearestNode(Node from) {
        if (nodeLinkDistance <= 0f) return null;
        Node best = null;
        float bestDist = nodeLinkDistance;
        for (Node other : nodes) {
            if (other == from) continue;
            float d = dist(from.x, from.y, other.x, other.y);
            if (d < bestDist) {
                bestDist = d;
                best = other;
            }
        }
        return best;
    }

    /**
     * Re-randomizes an arc's zig-zag, branch placement, and brightness. Called every
     * {@link #CRACKLE_INTERVAL} for the whole of its life, which is what makes it crackle.
     */
    private void crackle(Arc arc) {
        displace(arc.offsets, jaggedness);
        int segments = arc.offsets.length - 1;
        for (Branch b : arc.branches) {
            displace(b.offsets, jaggedness * 1.2f);
            if (b.index == 0 || RANDOM.nextFloat() < 0.35f) { // mostly keep forks in place, occasionally hop
                b.index = 2 + RANDOM.nextInt(segments - 4);
                b.angleOffset = (0.35f + RANDOM.nextFloat() * 0.55f) * (RANDOM.nextBoolean() ? 1f : -1f);
                b.lengthFraction = 0.25f + RANDOM.nextFloat() * 0.3f;
            }
        }
        arc.flicker = 0.55f + RANDOM.nextFloat() * 0.45f;
    }

    /**
     * Fills {@code offsets} with a midpoint-displacement zig-zag: perpendicular offsets,
     * as a fraction of the bolt's length, with both ends pinned at 0. Each level halves
     * the segment size and shrinks the displacement, giving detail at every scale.
     */
    private static void displace(float[] offsets, float roughness) {
        int n = offsets.length - 1;
        offsets[0] = 0f;
        offsets[n] = 0f;
        float amplitude = 0.16f * roughness;
        for (int step = n; step > 1; step /= 2) {
            int half = step / 2;
            for (int i = half; i < n; i += step) {
                offsets[i] = (offsets[i - half] + offsets[i + half]) * 0.5f + (RANDOM.nextFloat() * 2f - 1f) * amplitude;
            }
            amplitude *= 0.55f;
        }
    }

    /**
     * Computes an arc's live end point from its target, then its main and branch
     * polylines, into the arc's own point buffers. Done every update so arcs follow the
     * nodes, cursor, and wall they are attached to.
     */
    private void layoutArc(Arc arc) {
        float sx = arc.from.x, sy = arc.from.y;
        if (arc.to != null) {
            arc.endX = arc.to.x;
            arc.endY = arc.to.y;
        } else if (arc.toMouse) {
            if (mouseActive) {
                arc.endX = mouseX;
                arc.endY = mouseY;
            }
        } else {
            float len = arc.toWall ? distanceToWall(sx, sy, arc.angle) : arc.length;
            arc.endX = sx + (float) Math.cos(arc.angle) * len;
            arc.endY = sy + (float) Math.sin(arc.angle) * len;
        }

        float dx = arc.endX - sx, dy = arc.endY - sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        arc.dirAngle = (float) Math.atan2(dy, dx);
        arc.points = bolt(arc.points, arc.offsets, sx, sy, dx, dy, len);

        for (Branch b : arc.branches) {
            float px = arc.points[b.index * 2], py = arc.points[b.index * 2 + 1];
            float angle = arc.dirAngle + b.angleOffset;
            float branchLength = len * b.lengthFraction;
            float bdx = (float) Math.cos(angle) * branchLength;
            float bdy = (float) Math.sin(angle) * branchLength;
            b.points = bolt(b.points, b.offsets, px, py, bdx, bdy, branchLength);
        }
    }

    /**
     * Turns a straight run from (sx, sy) along (dx, dy) plus perpendicular {@code offsets} into packed x, y points.
     */
    private static float[] bolt(float[] out, float[] offsets, float sx, float sy, float dx, float dy, float len) {
        int count = offsets.length;
        if (out == null || out.length != count * 2) out = new float[count * 2];
        float nx = len > 0f ? -dy / len : 0f;
        float ny = len > 0f ? dx / len : 0f;
        for (int i = 0; i < count; i++) {
            float t = (float) i / (count - 1);
            float off = offsets[i] * len;
            out[i * 2] = sx + dx * t + nx * off;
            out[i * 2 + 1] = sy + dy * t + ny * off;
        }
        return out;
    }

    /**
     * Distance from (px, py) along {@code angle} to the edge of the current bounds.
     */
    private float distanceToWall(float px, float py, float angle) {
        float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
        float tx = dx > 0f ? (width - px) / dx : dx < 0f ? -px / dx : Float.MAX_VALUE;
        float ty = dy > 0f ? (height - py) / dy : dy < 0f ? -py / dy : Float.MAX_VALUE;
        return Math.max(0f, Math.min(tx, ty));
    }

    /**
     * Throws {@code count} sparks out of (x, y), fanned within about 80 degrees of {@code angle}.
     */
    private void emitSparks(float x, float y, float angle, int count) {
        for (int i = 0; i < count && sparks.size() < MAX_SPARKS; i++) {
            Spark s = new Spark();
            s.x = x;
            s.y = y;
            float a = angle + (RANDOM.nextFloat() * 2f - 1f) * 1.4f;
            float speed = 90f + RANDOM.nextFloat() * 220f;
            s.vx = (float) Math.cos(a) * speed;
            s.vy = (float) Math.sin(a) * speed;
            s.life = 0.25f + RANDOM.nextFloat() * 0.35f;
            sparks.add(s);
        }
    }

    @Override
    protected void renderEffect(Renderer renderer, float x, float y, float width, float height) {
        if (nodes == null) return; // no update() call has seeded the nodes yet

        renderer.pushClip(x, y, width, height);
        renderer.pushTranslate(x, y);

        // Wide glow passes for every arc first, so no arc's glow ever washes over another's bright core.
        if (glowColor != null) {
            for (Arc arc : arcs) drawArc(renderer, arc, glowColor, 10f, 0.1f);
            for (Arc arc : arcs) drawArc(renderer, arc, glowColor, 4f, 0.45f);
            for (Node n : nodes) drawNodeGlow(renderer, n);
        }
        if (coreColor != null) {
            for (Arc arc : arcs) drawArc(renderer, arc, coreColor, 1f, 0.95f);
        }
        for (Node n : nodes) drawNodeCore(renderer, n);
        drawSparks(renderer);

        renderer.popTranslate();
        renderer.popClip();
    }

    /**
     * One stroke pass over an arc and its branches at {@code widthScale} times the bolt thickness.
     */
    private void drawArc(Renderer renderer, Arc arc, Paint paint, float widthScale, float alpha) {
        float t = arc.age / arc.life;
        float fade = t < 0.15f ? 1f : 1f - (t - 0.15f) / 0.85f;
        float a = alpha * fade * arc.flicker;
        if (a <= 0f || arc.points == null) return;

        float thickness = boltThickness * widthScale;
        renderer.pushAlpha(a);
        renderer.drawPolyline(arc.points, arc.points.length / 2, paint, thickness);
        renderer.popAlpha();
        renderer.pushAlpha(a * 0.8f);
        for (Branch b : arc.branches) {
            if (b.points != null) renderer.drawPolyline(b.points, b.points.length / 2, paint, thickness * 0.6f);
        }
        renderer.popAlpha();
    }

    private void drawNodeGlow(Renderer renderer, Node n) {
        float pulse = 1f + 0.08f * (float) Math.sin(n.pulsePhase);
        float swell = pulse * (1f + n.flash * 0.35f);
        float layerAlpha = 0.045f + n.flash * 0.025f;
        // Largest first: stacked translucent disks approximate a soft radial falloff.
        renderer.pushAlpha(layerAlpha);
        for (int i = 0; i < GLOW_LAYERS; i++) {
            float f = (float) i / (GLOW_LAYERS - 1);
            float radius = nodeRadius * (5f - 3.6f * f) * swell;
            renderer.drawCircle(n.x, n.y, radius, glowColor);
        }
        renderer.popAlpha();
        renderer.pushAlpha(0.85f);
        renderer.drawCircle(n.x, n.y, nodeRadius * 1.15f * swell, glowColor);
        renderer.popAlpha();
    }

    private void drawNodeCore(Renderer renderer, Node n) {
        if (coreColor == null) return;
        float pulse = 1f + 0.08f * (float) Math.sin(n.pulsePhase);
        renderer.drawCircle(n.x, n.y, nodeRadius * 0.72f * pulse * (1f + n.flash * 0.2f), coreColor);
    }

    private void drawSparks(Renderer renderer) {
        if (sparks.isEmpty()) return;
        for (Spark s : sparks) {
            float a = 1f - s.age / s.life;
            // A short streak trailing behind the spark's motion reads as fast and hot.
            float tailX = s.x - s.vx * 0.035f;
            float tailY = s.y - s.vy * 0.035f;
            if (glowColor != null) {
                renderer.pushAlpha(a * 0.35f);
                renderer.drawLine(tailX, tailY, s.x, s.y, glowColor, boltThickness * 2f);
                renderer.popAlpha();
            }
            if (coreColor != null) {
                renderer.pushAlpha(a);
                renderer.drawLine(tailX, tailY, s.x, s.y, coreColor, boltThickness * 0.6f);
                renderer.popAlpha();
            }
        }
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Order-free O(1) removal: moves the last element into slot {@code i}.
     */
    private static <T> void removeSwap(List<T> list, int i) {
        int last = list.size() - 1;
        list.set(i, list.get(last));
        list.remove(last);
    }

    /**
     * A bouncing orb: position, velocity baked in at seed time, time until its next arc, and a flash that spikes when it fires or is struck.
     */
    private static final class Node {
        float x, y, vx, vy;
        float cooldown;
        float flash;
        float pulsePhase;
    }

    /**
     * One lightning bolt out of {@link #from}, toward another node, the cursor, or a
     * free direction. {@link #points} is the laid-out main bolt, rebuilt every update.
     */
    private static final class Arc {
        Node from;
        Node to;
        boolean toMouse;
        boolean toWall;
        float angle, length;
        float endX, endY, dirAngle;
        float[] offsets;
        float[] points;
        Branch[] branches;
        float age, life;
        float crackleTimer;
        float flicker;
    }

    /**
     * A thinner fork off an {@link Arc}, starting at main-bolt point {@link #index} and angled off the main direction.
     */
    private static final class Branch {
        int index;
        float angleOffset;
        float lengthFraction;
        float[] offsets;
        float[] points;
    }

    private static final class Spark {
        float x, y, vx, vy;
        float age, life;
    }
}
