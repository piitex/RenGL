package me.piitex.engine.ui.overlays;

/**
 * A {@link SliderOverlay} pinned to a 0-100 range, snapped to whole numbers, with a
 * "N%" label shown above the thumb. Everything else about {@link SliderOverlay} still
 * applies, including colors, thumb and track geometry, tick marks through
 * {@link #setTickSpacing}, and a different {@link #setLabelFormatter}. This subclass only
 * pre-configures the few setters that make a plain slider read as a percentage control,
 * the same way {@link me.piitex.engine.ui.theme.DarkTheme} pre-configures a few
 * {@link me.piitex.engine.ui.theme.BaseTheme} setters rather than being an unrelated
 * class.
 */
public class PercentSliderOverlay extends SliderOverlay {

    /**
     * A 0-100 slider, {@code width} points long, starting at 0%.
     */
    public PercentSliderOverlay(double width) {
        this(width, 0);
    }

    /**
     * A 0-100 slider, {@code width} points long, starting at {@code initialPercent} (clamped to [0, 100]).
     */
    public PercentSliderOverlay(double width, double initialPercent) {
        super(width, 20, 0, 100, initialPercent);
        setStep(1);
        setLabelFormatter(v -> Math.round(v) + "%");
    }
}
