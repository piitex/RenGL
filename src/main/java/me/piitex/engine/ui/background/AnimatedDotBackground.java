package me.piitex.engine.ui.background;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;

/**
 * A grid of dots that pulse, ripple, and cycle through the colour wheel, the animated
 * counterpart of {@link DotGridBackground}. See {@link Background} and
 * {@link me.piitex.engine.ui.Element#setBackground} for how to attach one.
 * <p>
 * Every frame each dot's size and hue are derived from its position and a running clock
 * (nothing is stored per dot, so resizing the owning Element just works). Three layers
 * are summed into one value in roughly [-1, 1]:
 * <ul>
 *   <li>a <b>ripple</b> travelling outward from the centre,</li>
 *   <li>a <b>diagonal wave</b> sweeping across the grid,</li>
 *   <li>a <b>swirl</b>, a wave keyed to the angle around the centre so it appears to rotate.</li>
 * </ul>
 * That value scales the dot radius and offsets its hue, so bands of colour and size
 * roll across the field. {@link #setSpeed} scales the clock, {@link #setIntensity} how
 * strongly the waves drive size and hue, and {@link #setHueSpan} how much of the colour
 * wheel is used. A smaller span, such as 0.15, gives a calm single-colour-family look
 * rather than the full rainbow.
 * <p>
 * Cost is one {@link Renderer#drawCircle} per dot, same as {@link DotGridBackground}.
 */
public class AnimatedDotBackground extends AbstractBackground {
    private float spacing = 26f;
    private float minRadius = 1f;
    private float maxRadius = 7f;
    private float speed = 1f;
    private float intensity = 1f;
    private float hueSpan = 1f;
    private float saturation = 0.85f;
    private float brightness = 1f;
    private float alpha = 0.85f;
    private float waveLength = 260f;

    private float time;

    public float getSpacing() {
        return spacing;
    }

    /** Distance between dots, in points. */
    public void setSpacing(float spacing) {
        this.spacing = Math.max(4f, spacing);
    }

    public float getMinRadius() {
        return minRadius;
    }

    public void setMinRadius(float minRadius) {
        this.minRadius = Math.max(0f, minRadius);
    }

    public float getMaxRadius() {
        return maxRadius;
    }

    /** Radius of a dot at the crest of a wave. Keep at or below half of {@link #getSpacing()} to avoid dots overlapping. */
    public void setMaxRadius(float maxRadius) {
        this.maxRadius = Math.max(0f, maxRadius);
    }

    public float getSpeed() {
        return speed;
    }

    /** Animation speed multiplier (default 1). Negative runs the waves backwards. */
    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public float getIntensity() {
        return intensity;
    }

    /** How strongly the waves drive dot size and hue, clamped to [0, 2] (default 1). */
    public void setIntensity(float intensity) {
        this.intensity = Math.max(0f, Math.min(2f, intensity));
    }

    public float getHueSpan() {
        return hueSpan;
    }

    /** Fraction of the colour wheel the waves sweep through, [0, 1] (default 1 = full rainbow). */
    public void setHueSpan(float hueSpan) {
        this.hueSpan = Math.max(0f, Math.min(1f, hueSpan));
    }

    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        this.saturation = Math.max(0f, Math.min(1f, saturation));
    }

    public float getBrightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = Math.max(0f, Math.min(1f, brightness));
    }

    public float getAlpha() {
        return alpha;
    }

    public void setAlpha(float alpha) {
        this.alpha = Math.max(0f, Math.min(1f, alpha));
    }

    public float getWaveLength() {
        return waveLength;
    }

    /** Distance in points between wave crests. Smaller values look busier, larger ones slow and rolling. */
    public void setWaveLength(float waveLength) {
        this.waveLength = Math.max(20f, waveLength);
    }

    @Override
    protected void updateEffect(float delta, float x, float y, float width, float height) {
        time += delta * speed;
    }

    @Override
    protected void renderEffect(Renderer renderer, float x, float y, float width, float height) {
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        float k = (float) (2 * Math.PI) / waveLength;

        renderer.pushClip(x, y, width, height);

        for (float dy = 0f; dy <= height; dy += spacing) {
            for (float dx = 0f; dx <= width; dx += spacing) {
                float ox = dx - cx;
                float oy = dy - cy;
                float dist = (float) Math.sqrt(ox * ox + oy * oy);
                float angle = (float) Math.atan2(oy, ox);

                float ripple = (float) Math.sin(dist * k - time * 2.2f);
                float diagonal = (float) Math.sin((dx + dy) * k * 0.7f + time * 1.4f);
                float swirl = (float) Math.sin(angle * 3f + dist * k * 0.5f + time * 1.1f);

                float wave = (ripple + diagonal + swirl) / 3f * intensity; // ~[-1, 1]
                float t = Math.max(0f, Math.min(1f, wave * 0.5f + 0.5f));

                float radius = minRadius + (maxRadius - minRadius) * t;
                float hue = (time * 0.05f + dist / (waveLength * 4f) + wave * 0.25f) * hueSpan;

                renderer.drawCircle(x + dx, y + dy, radius, hsv(hue, saturation, brightness, alpha));
            }
        }

        renderer.popClip();
    }

    /** HSV to {@link Color}; {@code hue} is in turns and wraps, so any float is valid. */
    private static Color hsv(float hue, float s, float v, float a) {
        float h = (hue - (float) Math.floor(hue)) * 6f;
        int sector = (int) h;
        float f = h - sector;
        float p = v * (1f - s);
        float q = v * (1f - s * f);
        float t = v * (1f - s * (1f - f));
        return switch (sector) {
            case 0 -> new Color(v, t, p, a);
            case 1 -> new Color(q, v, p, a);
            case 2 -> new Color(p, v, t, a);
            case 3 -> new Color(p, q, v, a);
            case 4 -> new Color(t, p, v, a);
            default -> new Color(v, p, q, a);
        };
    }
}
