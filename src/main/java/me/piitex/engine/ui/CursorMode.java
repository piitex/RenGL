package me.piitex.engine.ui;

/**
 * The OS mouse cursor shape an {@link Element} wants shown while it is hovered. It is
 * set through {@link Element#setCursorMode} and applied automatically by the engine (see
 * {@link me.piitex.engine.input.CursorManager}) whenever hover state changes, the same
 * way {@link Element#getStyling()}'s hover color applies itself. No polling or manual
 * wiring is needed; set it once per Element.
 */
public enum CursorMode {
    /**
     * The platform's ordinary pointer. This is also what is shown while nothing with a more specific mode is hovered.
     */
    DEFAULT,
    /**
     * An I-beam, the conventional hint that text here can be clicked into/selected.
     */
    TEXT,
    /**
     * A pointing hand, the conventional hint that this is clickable.
     */
    POINTER,

    CROSSHAIR,
    RESIZE_HORIZONTAL,
    RESIZE_VERTICAL,
    RESIZE_NWSE,
    RESIZE_NESW,
    /**
     * A four-way move/resize cursor.
     */
    RESIZE_ALL,
    /**
     * A "this action isn't available here" hint.
     */
    NOT_ALLOWED
}
