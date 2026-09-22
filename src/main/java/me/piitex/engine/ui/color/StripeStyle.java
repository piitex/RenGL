package me.piitex.engine.ui.color;

/**
 * How a {@link me.piitex.engine.ui.overlays.ProgressBarOverlay}'s diagonal stripes look and move. The bar reads it
 * every frame, so changing a setting takes effect immediately, including on a bar that is already showing them.
 * Every setter returns this, so it chains:
 * <pre>{@code
 * bar.setStripes(new StripeStyle().setColor(Color.LIGHT_GRAY).setWidth(10f).setSpeed(-40f));
 * }</pre>
 * Two independent switches: {@link #setEnabled} shows or hides the stripes altogether (the settings are kept), and
 * {@link #setAnimated} freezes them in place without hiding them.
 */
public class StripeStyle {
    private Color color = new Color(0.85f, 0.85f, 0.85f, 1f);
    private float width = 8f;
    private float speed = 30f;
    private boolean animated = true;
    private boolean enabled = true;

    /**
     * The stripe color; the bar's fill color shows in the gaps between stripes. Light gray by default.
     */
    public Color getColor() {
        return color;
    }

    public StripeStyle setColor(Color color) {
        if (color != null) this.color = color;
        return this;
    }

    /**
     * Width in points of each stripe, measured along the bar; the gap between stripes is the same. Default 8.
     */
    public float getWidth() {
        return width;
    }

    public StripeStyle setWidth(float width) {
        this.width = Math.max(1f, width);
        return this;
    }

    /**
     * Points per second the stripes move to the right; negative moves them left. Default 30.
     */
    public float getSpeed() {
        return speed;
    }

    public StripeStyle setSpeed(float speed) {
        this.speed = speed;
        return this;
    }

    /**
     * Whether the stripes move. When false they hold still where they are (and resume from there). Default true.
     */
    public boolean isAnimated() {
        return animated;
    }

    public StripeStyle setAnimated(boolean animated) {
        this.animated = animated;
        return this;
    }

    /**
     * Whether the stripes are drawn at all. Turning them off keeps every other setting. Default true.
     */
    public boolean isEnabled() {
        return enabled;
    }

    public StripeStyle setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }
}
