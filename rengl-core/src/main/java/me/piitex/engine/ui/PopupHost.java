package me.piitex.engine.ui;

import me.piitex.engine.ui.containers.Container;

/**
 * Where an element's floating panels open: the list of a dropdown, a context menu, a color or date picker. A
 * {@link me.piitex.engine.Window} is one, for the elements on it; so is a screen in a 3D scene
 * ({@code UiScreen}), for the elements on that. {@link Element#getPopupHost()} finds the one an element belongs to.
 * <p>
 * One popup is open at a time. It is a top-level {@link Container} placed in the host's own coordinates (the ones
 * {@link Element#getAbsoluteX()} gives), above everything else. A press outside it, or Escape, closes it, and the press is
 * used up rather than reaching what is underneath.
 */
public interface PopupHost {
    /**
     * Opens {@code popup}, closing any popup that is open. It follows {@code anchor} (null to stay where it was placed) and
     * {@code onDismiss} runs whenever it closes, by any route. See {@link me.piitex.engine.Window#showPopup}.
     */
    void showPopup(Container popup, Element anchor, Runnable onDismiss);

    /**
     * Closes the open popup, if any, running its dismiss callback.
     */
    void closePopup();

    /**
     * The open popup, or null.
     */
    Container getPopup();

    /**
     * The room popups have, as {x, y, width, height} in the host's coordinates: the whole window, or the container shown on
     * a screen. A popup is kept inside it.
     */
    double[] getPopupArea();

    /**
     * The element that has the keyboard on this host, or null.
     */
    Element getFocusedElement();

    /**
     * Gives the keyboard to {@code element} (null takes it away), telling the one that had it.
     */
    void requestFocus(Element element);
}
