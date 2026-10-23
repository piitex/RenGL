package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Anchor;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Where an element is pinned in its container, as a layout file names it: one of the nine {@link Anchor} points, or
 * {@link #NONE} to leave the element where its x and y put it.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public enum UiAnchor {
    NONE(null),
    TOP_LEFT(Anchor.TOP_LEFT), TOP_CENTER(Anchor.TOP_CENTER), TOP_RIGHT(Anchor.TOP_RIGHT),
    CENTER_LEFT(Anchor.CENTER_LEFT), CENTER(Anchor.CENTER), CENTER_RIGHT(Anchor.CENTER_RIGHT),
    BOTTOM_LEFT(Anchor.BOTTOM_LEFT), BOTTOM_CENTER(Anchor.BOTTOM_CENTER), BOTTOM_RIGHT(Anchor.BOTTOM_RIGHT);

    private final Anchor point;

    UiAnchor(Anchor point) {
        this.point = point;
    }

    /** The engine's anchor for this, or null for {@link #NONE}. */
    public Anchor point() {
        return point;
    }

    /** The constant for {@code anchor}: {@link #NONE} for null or a point that isn't one of the nine. */
    public static UiAnchor of(Anchor anchor) {
        if (anchor == null) return NONE;
        for (UiAnchor a : values()) if (anchor.equals(a.point)) return a;
        return NONE;
    }
}
