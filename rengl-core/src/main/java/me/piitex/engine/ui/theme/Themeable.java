package me.piitex.engine.ui.theme;

/**
 * Implemented by an Element whose chrome (backgrounds, borders, caret/placeholder/
 * selection colors, ...) comes from a {@link Theme} instead of its own hardcoded
 * defaults, such as {@link me.piitex.engine.ui.overlays.TextFieldOverlay} and
 * {@link me.piitex.engine.ui.overlays.ButtonOverlay}. Register with {@link
 * ThemeManager#register(Themeable)} once, typically at the end of the constructor;
 * that applies the current theme immediately and keeps it applied on every future
 * {@link ThemeManager#setCurrent(Theme)}.
 */
public interface Themeable {

    /**
     * Applies {@code theme}'s tokens to this Element as a baseline. A {@link Theme} never
     * overrides an explicit, application-driven customization, only whatever has not been
     * customized that way. Called once at registration
     * (with whatever theme is current then) and again, every time this Element is
     * still alive, whenever {@link ThemeManager#setCurrent(Theme)} runs.
     * <p>
     * Implementations should route every property they own through an {@link
     * Overridable} (directly, or via a wrapper like {@link
     * me.piitex.engine.ui.color.Styling}'s {@code applyThemeXxx} methods) rather than
     * writing straight to a field or calling that property's own public setter. The
     * public setter pins a property against every future call here, which is the point
     * for application code but would make a constructor seeding its own starting
     * appearance block itself. See {@link Overridable} for the full rule.
     */
    void applyTheme(Theme theme);
}
