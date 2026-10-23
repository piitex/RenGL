package me.piitex.engine.ui.theme;

/**
 * A single themed property that remembers whether it is still following the current
 * {@link Theme} or has been pinned by an explicit, application-driven set. The rule is
 * that a {@link Theme} is a baseline only, and an explicit set always wins. This is the
 * primitive every {@link Themeable} class's themed fields are built from, including
 * {@link me.piitex.engine.ui.color.Styling}'s colors and geometry, a
 * {@link me.piitex.engine.ui.overlays.TextOverlay}'s text color,
 * {@link me.piitex.engine.ui.text.TextEditingOptions}' caret, placeholder, and
 * selection colors, and {@link me.piitex.engine.ui.scroll.ScrollBarStyle}'s track and
 * thumb colors. Using it keeps each of those from repeating its own bookkeeping for
 * whether a property is still themed.
 * <p>
 * The two write paths are deliberately asymmetric:
 * <ul>
 *     <li>{@link #set} is what a public, application-facing setter (e.g. {@code
 *     Styling#setBackgroundColor}) calls. It always takes effect, and pins the value
 *     against every future {@link #applyTheme} call, so an explicit customization
 *     survives a theme switch rather than being discarded by it.</li>
 *     <li>{@link #applyTheme} is what the owning class's own {@code applyTheme(Theme)}
 *     calls instead of the public setter. It is a no-op once {@link #set} has pinned
 *     this property, which is the point: a theme switch re-themes everything still
 *     following the theme and leaves everything pinned alone.</li>
 * </ul>
 * A fresh instance starts unpinned, so a constructor can seed a starting value (e.g.
 * a constructor argument, or a class's own hardcoded default appearance) through
 * {@link #applyTheme} without that seed permanently blocking the real theme
 * application that follows moments later in the same constructor, via {@code
 * ThemeManager.register(this)}.
 * <p>
 * The last theme value is remembered even while pinned, so {@link #clearExplicit}
 * can put it back straight away.
 */
public final class Overridable<T> {
    private T value;
    private T themeValue;
    private boolean explicit;

    public Overridable(T initialValue) {
        this.value = initialValue;
        this.themeValue = initialValue;
    }

    public T get() {
        return value;
    }

    /**
     * An explicit, application-driven set. This pins {@code value} against every future {@link #applyTheme} call.
     */
    public void set(T value) {
        this.value = value;
        this.explicit = true;
    }

    /**
     * A Theme-driven set. This is a no-op once {@link #set} has pinned this property.
     */
    public void applyTheme(T value) {
        this.themeValue = value;
        if (!explicit) {
            this.value = value;
        }
    }

    /**
     * Releases a pin made by {@link #set}, so this property follows the theme again. The value goes back to
     * the last one {@link #applyTheme} gave, or the starting value if the theme never set one.
     */
    public void clearExplicit() {
        this.explicit = false;
        this.value = themeValue;
    }

    /**
     * Whether {@link #set} has pinned this property against theme changes.
     */
    public boolean isExplicit() {
        return explicit;
    }
}
