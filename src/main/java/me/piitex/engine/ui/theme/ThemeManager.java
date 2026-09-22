package me.piitex.engine.ui.theme;

import me.piitex.engine.ui.text.Font;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The global theme registry, following the same static-utility shape as {@link Font},
 * {@link me.piitex.engine.input.Clipboard}, and
 * {@link me.piitex.engine.input.CursorManager} elsewhere in this engine.
 * <p>
 * Every {@link Themeable} Element registers itself once, via {@link
 * #register(Themeable)}, typically at the end of its own constructor. From then on it
 * gets re-{@link Themeable#applyTheme}'d automatically every time {@link
 * #setCurrent(Theme)} runs, for instance from a dark-mode toggle button's
 * {@code onAction}. Switching the theme at runtime therefore re-colors what is already
 * on screen, not only what is constructed afterward.
 * <p>
 * The registry holds only weak references, so a Themeable Element doesn't need to be
 * explicitly deregistered to be collected once nothing else references it.
 */
public final class ThemeManager {
    private static volatile Theme current = new LightTheme();
    private static final Set<Themeable> registry = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private ThemeManager() {
    }

    /**
     * The theme every {@link Themeable} Element is currently styled with. Never null.
     */
    public static Theme getCurrent() {
        return current;
    }

    /**
     * Makes {@code theme} current and immediately re-applies it to every Themeable
     * Element still registered. See the class docs. Passing null resets to a fresh
     * {@link LightTheme}.
     */
    public static void setCurrent(Theme theme) {
        current = theme == null ? new LightTheme() : theme;

        List<Themeable> snapshot;
        synchronized (registry) {
            snapshot = new ArrayList<>(registry);
        }
        for (Themeable themeable : snapshot) {
            themeable.applyTheme(current);
        }
    }

    /**
     * Registers {@code themeable} for future {@link #setCurrent} calls, and immediately applies the current theme to it. A no-op if {@code themeable} is null.
     */
    public static void register(Themeable themeable) {
        if (themeable == null) return;
        registry.add(themeable);
        themeable.applyTheme(current);
    }
}
