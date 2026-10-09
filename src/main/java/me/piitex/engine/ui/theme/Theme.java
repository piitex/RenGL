package me.piitex.engine.ui.theme;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;

import java.nio.file.Path;
import java.util.Map;

/**
 * A named bundle of design tokens: the colors and geometry that {@link Themeable}
 * objects pull their chrome from instead of each hardcoding its own defaults. Those
 * objects are {@link me.piitex.engine.ui.overlays.TextFieldOverlay},
 * {@link ButtonOverlay}, a {@link Container} opted in through
 * {@link Container#useTheme()}, and {@link me.piitex.engine.Window} itself.
 * <p>
 * This is a read-only contract: every {@link Themeable#applyTheme} implementation only
 * calls these getters and never mutates a Theme. Any class can therefore serve as a
 * Theme, not only those built on this engine's own base. Usually that base is
 * {@link BaseTheme}, a mutable value object with a setter for every token below, which
 * can be extended or built once and adjusted. Its two built-in subclasses are
 * {@link LightTheme} and {@link DarkTheme}. Implementing this interface directly gives
 * full control, for instance to compute tokens at runtime from another source rather
 * than from fixed fields.
 * <p>
 * Hand a Theme to {@link ThemeManager#setCurrent(Theme)}, such as
 * {@code ThemeManager.setCurrent(new DarkTheme())} or
 * {@code ThemeManager.setCurrent(new MyCompanyTheme())}, to make it current across
 * every registered {@link Themeable} Element at once.
 * <p>
 * This interface does not include a primary text or label color. Every overlay that
 * draws one takes it as an explicit constructor argument, so it stays a per-instance
 * application choice rather than something a global theme overrides.
 */
public interface Theme {

    /**
     * The text/label color for a themed control (a {@link
     * me.piitex.engine.ui.overlays.TextFieldOverlay}'s typed text, a {@link
     * ButtonOverlay}'s label). A standalone
     * {@link me.piitex.engine.ui.overlays.TextOverlay} is not {@link Themeable}, so it
     * keeps whatever color it was given.
     */
    Color getText();

    /**
     * A {@link me.piitex.engine.ui.overlays.TextFieldOverlay}'s background at rest.
     */
    Color getFieldBackground();

    /**
     * A {@link me.piitex.engine.ui.overlays.TextFieldOverlay}'s background while hovered.
     */
    Color getFieldHoverBackground();

    Color getFieldBorder();

    Color getPlaceholderText();

    Color getCaret();

    Color getSelection();

    /**
     * A {@link ButtonOverlay}'s background at rest.
     */
    Color getButtonBackground();

    /**
     * A {@link ButtonOverlay}'s background while hovered.
     */
    Color getButtonHoverBackground();

    Color getButtonBorder();

    /**
     * A {@link Container}'s background, applied only to a Container that has opted into
     * theming through {@link Container#useTheme()}. Most Containers exist purely to
     * group and position children and stay untouched by this. Forcing every one of them
     * into an opaque themed panel by default would break the common case of a Container
     * meant to stay transparent, such as a full-window root Container sitting in front
     * of {@link me.piitex.engine.Window#setBackgroundColor}.
     * <p>
     * It is a {@link Paint}, so panels can be a gradient. An implementation can still return a plain {@link Color}.
     */
    Paint getContainerBackground();

    Color getContainerBorder();

    Color getScrollThumb();

    Color getScrollTrack();

    /**
     * What a {@link me.piitex.engine.Window}'s canvas is cleared to every frame,
     * behind everything else. See
     * {@link me.piitex.engine.Window#getBackgroundColor()}. This is a plain
     * {@link Color}, or a {@link me.piitex.engine.ui.color.LinearGradient} or
     * {@link me.piitex.engine.ui.color.ScrollingGradient} for a gradient backdrop.
     * It is a separate token from {@link #getContainerBackground()} because a window
     * backdrop and a panel floating on top of it usually need distinct shades. In dark
     * mode the window is near-black, a few steps darker than a panel sitting on it, for
     * visual separation.
     */
    Paint getWindowBackground();

    /**
     * An effect drawn behind the window's contents, over {@link #getWindowBackground()}: a moving field of dots, a
     * picture. It is the settings of one of the {@link me.piitex.engine.ui.background.BackgroundKinds kinds}, as that
     * class writes them, so a theme can be a file, or null for none. A window makes its own background from them rather
     * than sharing one, as an effect has state of its own, and a pinned
     * {@link me.piitex.engine.Window#setBackground} is left alone.
     */
    default Map<String, Object> getWindowEffect() {
        return null;
    }

    /**
     * An effect drawn behind a themed {@link Container}'s own contents, in the same form as {@link #getWindowEffect()}.
     * Each container makes its own.
     */
    default Map<String, Object> getContainerEffect() {
        return null;
    }

    /**
     * The folder the pictures an effect names are looked for in: the project's, for a theme loaded from a file. The
     * working folder by default. This is not a setting, so it isn't a token of {@link ThemeFile}.
     */
    default Path assetBase() {
        return Path.of("");
    }

    /**
     * Shared corner radius for every themed control's box.
     */
    float getCornerRadius();

    /**
     * Shared border thickness for every themed control's box.
     */
    float getBorderThickness();

    /**
     * Fill of the bubble {@link me.piitex.engine.ui.Element#getTooltip()} shows.
     */
    Paint getTooltipBackground();

    Paint getTooltipBorder();

    Paint getTooltipText();
}
