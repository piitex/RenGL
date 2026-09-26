package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.animation.SpinTransition;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;

/**
 * A "working on it" indicator: a ring of dots with a fading comet trail chasing
 * around it. It holds no state beyond whether it is running: the motion is a looping
 * {@link SpinTransition} from the engine's animation system, started on construction and
 * controlled with {@link #setSpinning}. Use {@link ProgressBarOverlay} instead when the
 * amount done is known.
 * <p>
 * The spinner has no track or box of its own (it draws nothing but the dots) and is
 * colored like {@link ProgressBarOverlay}'s fill; {@link #setColor} pins it against
 * theme switches. It is sized by its width and height, drawing in the smaller of the two.
 */
public class LoadingSpinnerOverlay extends Overlay implements Themeable {
    private static final float PERIOD_SECONDS = 1f;

    private final Overridable<Color> color = new Overridable<>(Color.GRAY);
    private SpinTransition activeSpin;

    /**
     * A 32x32 spinner, already spinning.
     */
    public LoadingSpinnerOverlay() {
        this(32);
    }

    /**
     * A square spinner {@code size} points across, already spinning.
     */
    public LoadingSpinnerOverlay(double size) {
        setWidth(size);
        setHeight(size);

        // Nothing but the animation is drawn, so a transparent baseline, not pinned.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        ThemeManager.register(this);

        setSpinning(true);
    }

    public boolean isSpinning() {
        return activeSpin != null;
    }

    /**
     * Starts or stops the animation. A stopped spinner draws nothing; hide it with {@code setEnabled(false)} to also skip layout.
     */
    public void setSpinning(boolean spinning) {
        if (spinning == isSpinning()) return;
        if (spinning) {
            activeSpin = new SpinTransition(PERIOD_SECONDS);
            playTransition(activeSpin);
        } else {
            activeSpin.stop();
            activeSpin = null;
        }
    }

    public Color getColor() {
        return color.get();
    }

    /**
     * Pins the dot color against every future theme switch. See {@link Overridable}.
     */
    public void setColor(Color color) {
        this.color.set(color);
    }

    /**
     * Themes the dot color as a baseline. This is a no-op once {@link #setColor} has been called.
     */
    @Override
    public void applyTheme(Theme theme) {
        color.applyTheme(theme.getScrollThumb());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        color.clearExplicit();
    }
}
