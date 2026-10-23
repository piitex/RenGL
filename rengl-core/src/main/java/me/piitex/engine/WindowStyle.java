package me.piitex.engine;

/**
 * The frame the OS draws around a window. Set it up front with {@link WindowOptions#setStyle} or change it while
 * the app is running with {@link Window#setStyle}.
 */
public enum WindowStyle {

    /**
     * The normal OS frame: title bar, title text, and the close / minimize / maximize controls.
     */
    STANDARD,

    /**
     * No frame at all: no title bar, no title text, no window controls.
     * The window is just its content, which suits a splash screen.
     */
    BORDERLESS
}
