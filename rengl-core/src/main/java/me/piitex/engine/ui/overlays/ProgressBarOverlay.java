package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.ui.animation.ProgressTransition;
import me.piitex.engine.ui.animation.StripeTransition;
import me.piitex.engine.ui.animation.SweepTransition;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.StripeStyle;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;

/**
 * A horizontal bar that fills left to right as a task progresses, or sweeps
 * continuously when the amount of work is not known. The bar itself does very little:
 * it holds a progress value in [0, 1] and draws a track plus a fill. Every moving part
 * is a {@link me.piitex.engine.ui.animation.Transition} from the engine's animation
 * system:
 * <ul>
 *   <li>{@link #setProgress} jumps straight to a value</li>
 *   <li>{@link #animateTo} plays a {@link ProgressTransition}, easing there over time</li>
 *   <li>{@link #setIndeterminate} plays a looping {@link SweepTransition}</li>
 *   <li>{@link #setStripes} paints moving diagonal stripes over the fill, driven by a {@link StripeTransition}</li>
 *   <li>anything else, such as a shimmer, a pulse, or a glow at the fill's leading
 *       edge, is a {@code Transition} of your own started with
 *       {@link #playTransition}, which can read {@link #getProgress()} and
 *       {@link #getFillWidth()} to line itself up with the fill</li>
 * </ul>
 * The track is themed like {@link SliderOverlay}'s (scroll-track color) and the fill
 * like its fill (the theme's accent); the ends round to {@link #getStyling()}'s corner
 * radius, capped at half the bar's height.
 */
public class ProgressBarOverlay extends Overlay implements Themeable {
    private double progress;
    private final Overridable<Paint> fillColor = new Overridable<>(Color.GRAY);
    private ProgressTransition activeTween;
    private SweepTransition activeSweep;

    private StripeStyle stripes;
    private float stripeOffset;
    private StripeTransition activeStripes;

    /**
     * A 200x10 bar at 0%.
     */
    public ProgressBarOverlay() {
        this(200, 10);
    }

    public ProgressBarOverlay(double width, double height) {
        setWidth(width);
        setHeight(height);

        // Baseline seeds, not pinned. See Overridable. The track is the Styling
        // background, along with its hover color kept identical, themed in applyTheme.
        getStyling().applyThemeBorderThickness(0f);
        ThemeManager.register(this);
    }

    /**
     * Current progress in [0, 1]. Mid-tween, this is the in-between value being drawn.
     */
    public double getProgress() {
        return progress;
    }

    /**
     * Jumps to {@code progress} (clamped to [0, 1]) immediately, cancelling any running {@link #animateTo}.
     */
    public void setProgress(double progress) {
        cancelTween();
        applyAnimatedProgress(progress);
    }

    /**
     * Eases to {@code target} over {@code seconds} with {@link Easing#EASE_IN_OUT}.
     */
    public void animateTo(double target, float seconds) {
        animateTo(target, seconds, Easing.EASE_IN_OUT, null);
    }

    /**
     * Eases from the current progress to {@code target} over {@code seconds}, replacing
     * any tween already running (it stops where it is and this one continues from
     * there). Also leaves indeterminate mode.
     *
     * @param onComplete fired when the target is reached, not if another call/{@link #setProgress} interrupts; may be null
     */
    public void animateTo(double target, float seconds, Easing easing, Runnable onComplete) {
        setIndeterminate(false);
        cancelTween();
        activeTween = new ProgressTransition(target, seconds, easing, onComplete);
        playTransition(activeTween);
    }

    /**
     * Whether the looping sweep is running instead of a determinate fill.
     */
    public boolean isIndeterminate() {
        return activeSweep != null;
    }

    /**
     * Switches between the looping sweep (true) and the determinate fill (false); either way stops any running {@link #animateTo}.
     */
    public void setIndeterminate(boolean indeterminate) {
        if (indeterminate == isIndeterminate()) return;
        if (indeterminate) {
            cancelTween();
            activeSweep = new SweepTransition(2f);
            playTransition(activeSweep);
        } else {
            activeSweep.stop();
            activeSweep = null;
        }
    }

    /**
     * Sets the drawn progress without touching running transitions. This is what
     * {@link ProgressTransition} calls every frame. Prefer {@link #setProgress}.
     */
    public void applyAnimatedProgress(double progress) {
        this.progress = Math.max(0, Math.min(1, progress));
    }

    /**
     * Width in points of the fill as currently drawn, for custom transitions that draw along it.
     */
    public double getFillWidth() {
        return getWidth() * progress;
    }

    /**
     * Draws animated diagonal stripes in {@code stripeColor} over the fill (which supplies the other color), with the default {@link StripeStyle} width and speed.
     */
    public void setStripes(Color stripeColor) {
        setStripes(new StripeStyle().setColor(stripeColor));
    }

    /**
     * Draws animated diagonal stripes over the fill: the fill color and {@code stripeColor} alternate, so
     * {@code setFillColor(green); setStripes(lightGray, 8f, 30f)} gives a green/gray barber pole.
     *
     * @param stripeWidth width in points of each stripe measured along the bar (the gap between stripes is the same)
     * @param speed       points per second the stripes move right; negative moves left, 0 holds them still
     */
    public void setStripes(Color stripeColor, float stripeWidth, float speed) {
        setStripes(new StripeStyle().setColor(stripeColor).setWidth(stripeWidth).setSpeed(speed));
    }

    /**
     * Draws the stripes described by {@code style}, or removes them if it is null. The bar keeps a reference and
     * reads it every frame, so later changes to the style ({@code style.setSpeed(...)}, {@code setAnimated(false)},
     * {@code setEnabled(false)}) apply live. Works with a determinate bar at any progress: the stripes cover only
     * the filled part.
     */
    public void setStripes(StripeStyle style) {
        if (style == null) {
            clearStripes();
            return;
        }
        stripes = style;
        if (activeStripes == null) {
            activeStripes = new StripeTransition();
            playTransition(activeStripes);
        }
    }

    /**
     * The style being drawn (edit it live), or null if none was set.
     */
    public StripeStyle getStripes() {
        return stripes;
    }

    /**
     * Removes the stripes entirely and goes back to a solid fill. To hide them but keep their settings, use {@code getStripes().setEnabled(false)}.
     */
    public void clearStripes() {
        stripes = null;
        if (activeStripes != null) {
            activeStripes.stop();
            activeStripes = null;
        }
    }

    /**
     * Whether stripes are set and enabled.
     */
    public boolean isStriped() {
        return stripes != null && stripes.isEnabled();
    }

    /**
     * Moves the stripes on by {@code delta} seconds unless they are frozen. This is what {@link StripeTransition} calls every frame.
     */
    public void advanceStripes(float delta) {
        StripeStyle style = stripes;
        if (style == null || !style.isAnimated() || !style.isEnabled()) return;
        float period = 2f * style.getWidth();
        stripeOffset = (stripeOffset + style.getSpeed() * delta) % period;
        if (stripeOffset < 0) stripeOffset += period;
    }

    private void cancelTween() {
        if (activeTween != null) {
            activeTween.cancel();
            activeTween = null;
        }
    }

    public Paint getFillColor() {
        return fillColor.get();
    }

    /**
     * Pins the fill color against every future theme switch. See {@link Overridable}.
     */
    public void setFillColor(Paint fillColor) {
        this.fillColor.set(fillColor);
    }

    /**
     * Themes the track and fill as a baseline, skipping anything already pinned. See {@link Overridable}.
     */
    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(fillColor.get(), delta);
    }

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getScrollTrack());
        getStyling().applyThemeHoverColor(theme.getScrollTrack()); // same as the track: a bar has no hover state
        getStyling().applyThemeCornerRadius(theme.getCornerRadius());
        fillColor.applyTheme(theme.getAccent());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        fillColor.clearExplicit();
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        super.render(renderer); // the track

        // Indeterminate mode replaces the fill: a full bar would hide the sweep entirely,
        // since both are drawn in the same color.
        if (isIndeterminate()) return;

        float fill = (float) getFillWidth();
        if (fill <= 0f) return;
        float h = (float) getHeight();
        float radius = Math.min(Math.min(getStyling().getCornerRadius(), h / 2f), fill / 2f);
        float x = (float) getX();
        float y = (float) getY();
        renderer.drawQuad(x, y, fill, h, fillColor.get(), radius);

        if (isStriped()) {
            drawStripes(renderer, x, y, fill, h, radius);
        }
    }

    /**
     * 45 degree stripes across the fill. The clip is a plain rectangle, so it stops {@code radius} short of each
     * end: the rounded caps keep the fill color instead of having stripe corners poke out past the curve.
     */
    private void drawStripes(Renderer renderer, float x, float y, float fill, float h, float radius) {
        float clipWidth = fill - 2f * radius;
        if (clipWidth <= 0f) return;

        StripeStyle style = stripes;
        float period = 2f * style.getWidth();
        float thickness = style.getWidth() * 0.7071f; // the stripe's width across its own direction, for a horizontal width of stripeWidth
        float offset = stripeOffset % period; // the width may have been edited since the last advance
        renderer.pushClip(x + radius, y, clipWidth, h);
        for (float sx = x - h - period + offset; sx < x + fill; sx += period) {
            renderer.drawLine(sx, y + h, sx + h, y, style.getColor(), thickness);
        }
        renderer.popClip();
    }

    // --- Accessibility ---

    @Override
    protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
        return me.piitex.engine.access.AccessibleRole.PROGRESS_BAR;
    }

    @Override
    public String getAccessibleValue() {
        return isIndeterminate() ? null : Math.round(getProgress() * 100) + " percent";
    }
}
