package me.piitex.engine.ui.color;

/**
 * A {@link Color} that cycles through the colour wheel by itself, the "RGB" effect. It is
 * a real Color subclass, so it works anywhere a Color or {@link Paint} is accepted: an
 * element's background, border, or text color, a background effect's colors such as
 * {@link me.piitex.engine.ui.background.ElectricNodeBackground#setGlowColor}, or a
 * direct {@link me.piitex.engine.gl.renderer.Renderer} call.
 * <pre>{@code
 * RainbowColor rgb = new RainbowColor(); // full cycle every 6 seconds
 * title.setTextColor(rgb);
 * panel.getStyling().setBorderColor(rgb);
 * electric.setGlowColor(rgb);
 * }</pre>
 * <p>
 * The hue comes from a clock rather than from {@link AnimatedPaint#advance}, so one
 * instance can be shared by any number of elements without cycling faster: an
 * AnimatedPaint is advanced once per element using it, which is why
 * {@link ScrollingGradient} must not be shared. Every RainbowColor also runs off the same
 * start time, so two instances with the same {@link #getCycleDuration()} and
 * {@link #getHueOffset()} show the same colour no matter when each was created. Give one
 * a {@link #setHueOffset} to keep it a fixed distance around the wheel from the others,
 * for example 0.5 for the complementary colour, or lower {@link #setSaturation} for a
 * pale version that still tracks the same hue.
 * <p>
 * The colour is recomputed at most once per millisecond and cached, so the separate
 * {@link #getR()}, {@link #getG()}, and {@link #getB()} reads made while drawing one
 * frame always agree with each other.
 */
public class RainbowColor extends Color {
    private static final long EPOCH_NANOS = System.nanoTime();
    private static final long REFRESH_NANOS = 1_000_000L;

    private float cycleDuration;
    private float saturation;
    private float brightness;
    private float alpha;
    private float hueOffset;

    // The hue, in turns, is anchorPhase + (now - anchorNanos) / cycleDuration. Changing the
    // cycle duration re-anchors at the current phase, so the colour never jumps.
    private double anchorPhase;
    private long anchorNanos = EPOCH_NANOS;

    private final float[] rgb = new float[3];
    private float hue;
    private long computedAtNanos;
    private boolean stale = true;

    /**
     * A fully saturated, full-brightness, opaque rainbow that cycles every 6 seconds.
     */
    public RainbowColor() {
        this(6f);
    }

    /**
     * As {@link #RainbowColor()}, taking {@code cycleDuration} seconds per full trip around the wheel.
     */
    public RainbowColor(float cycleDuration) {
        this(cycleDuration, 1f, 1f, 1f);
    }

    public RainbowColor(float cycleDuration, float saturation, float brightness, float alpha) {
        super(1f, 0f, 0f, 1f); // unused: every getter below is overridden
        this.cycleDuration = cycleDuration;
        this.saturation = clamp(saturation);
        this.brightness = clamp(brightness);
        this.alpha = clamp(alpha);
    }

    /**
     * Seconds per full trip around the colour wheel (default 6). A negative value cycles
     * backwards and 0 freezes on the current colour. Changing this continues from the
     * current colour rather than jumping.
     */
    public float getCycleDuration() {
        return cycleDuration;
    }

    public void setCycleDuration(float cycleDuration) {
        long now = System.nanoTime();
        anchorPhase = phaseAt(now);
        anchorNanos = now;
        this.cycleDuration = cycleDuration;
        stale = true;
    }

    /**
     * Colour saturation in [0, 1] (default 1). Lower values give pastel versions of the same hue.
     */
    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        this.saturation = clamp(saturation);
        stale = true;
    }

    /**
     * Colour brightness in [0, 1] (default 1).
     */
    public float getBrightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = clamp(brightness);
        stale = true;
    }

    /**
     * Opacity in [0, 1] (default 1). This is what {@link #getA()} returns.
     */
    public float getAlpha() {
        return alpha;
    }

    public void setAlpha(float alpha) {
        this.alpha = clamp(alpha);
    }

    /**
     * A fixed shift around the wheel, in turns, added to the cycling hue (default 0).
     * 0.5 is the complementary colour, and 1/3 or 2/3 give a triad. See the class docs.
     */
    public float getHueOffset() {
        return hueOffset;
    }

    public void setHueOffset(float hueOffset) {
        this.hueOffset = hueOffset;
        stale = true;
    }

    /**
     * The current hue in turns, [0, 1), where 0 is red, 1/3 green, and 2/3 blue.
     */
    public float getHue() {
        refresh();
        return hue;
    }

    /**
     * A plain, unchanging Color of what this shows right now, for code that needs to keep the current colour.
     */
    public Color snapshot() {
        refresh();
        return new Color(rgb[0], rgb[1], rgb[2], alpha);
    }

    @Override
    public float getR() {
        refresh();
        return rgb[0];
    }

    @Override
    public float getG() {
        refresh();
        return rgb[1];
    }

    @Override
    public float getB() {
        refresh();
        return rgb[2];
    }

    @Override
    public float getA() {
        return alpha;
    }

    @Override
    public int toARGB() {
        refresh();
        return ((int) (alpha * 255) << 24) | ((int) (rgb[0] * 255) << 16) | ((int) (rgb[1] * 255) << 8) | (int) (rgb[2] * 255);
    }

    @Override
    public String toString() {
        refresh();
        return "Rainbow(" + rgb[0] + "," + rgb[1] + "," + rgb[2] + "," + alpha + ")";
    }

    private double phaseAt(long nanos) {
        if (cycleDuration == 0f) return anchorPhase;
        return anchorPhase + (nanos - anchorNanos) / 1_000_000_000.0 / cycleDuration;
    }

    private void refresh() {
        long now = System.nanoTime();
        if (!stale && now - computedAtNanos < REFRESH_NANOS) return;

        double phase = phaseAt(now) + hueOffset;
        hue = (float) (phase - Math.floor(phase));
        hsvToRgb(hue, saturation, brightness, rgb);
        computedAtNanos = now;
        stale = false;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
