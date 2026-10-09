package me.piitex.engine.ui.menu;

import me.piitex.engine.Window;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A floating list of {@link MenuItem}s shown at a point in the window. It is the
 * building block under both the right-click {@link Element#setContextMenu context menu}
 * and each menu of a {@link MenuBarOverlay}, and is usable on its own through
 * {@link #show}, {@link #showAtMouse}, and {@link #showBelow} for things like a "more"
 * button.
 * <p>
 * Shown through {@link Window#showPopup}, so it paints above everything, dismisses on an
 * outside click or Escape, and only one is ever open. Submenus cascade to the side inside
 * that same popup. Keyboard: Up/Down move, Right/Enter open a submenu or choose, Left/
 * Escape close one level, and the mouse works as expected (hovering a submenu row opens
 * it after a short delay so the pointer can cross neighbouring rows on its way).
 * <p>
 * Colors come from the active {@link Theme} at the moment the menu opens (the field
 * tokens the dropdown list uses, with {@link Theme#getSelection()} as the highlight);
 * the setters here pin a color instead. A menu taller than the window is clamped to the
 * top edge but does not scroll.
 * <p>
 * A PopupMenu is a plain object rather than an Element, so one can be built, kept, and
 * shown as often as needed. Use {@link #onOpen} to refresh item state just before each
 * show.
 */
public class PopupMenu {
    private final List<MenuItem> items = new ArrayList<>();
    private float fontSize = 15f;
    private float itemHeight = 28f;
    private float minWidth = 160f;
    private Paint backgroundColor;
    private Paint hoverColor;
    private Paint textColor;
    private Paint disabledTextColor;
    private Paint borderColor;
    private Consumer<PopupMenu> onOpen;
    private Runnable onClose;

    private MenuPopup popup;
    /**
     * Set by {@link MenuBarOverlay}: called with -1/+1 when Left/Right is pressed with no submenu to enter or leave.
     */
    IntConsumer sidewaysListener;
    /**
     * Set by {@link MenuBarOverlay} to learn when this menu closes, alongside the user's {@link #onClose}.
     */
    Runnable closeListener;

    public PopupMenu(MenuItem... items) {
        Collections.addAll(this.items, items);
    }

    public PopupMenu add(MenuItem item) {
        items.add(item);
        return this;
    }

    /**
     * Shorthand for {@code add(new MenuItem(label, action))}.
     */
    public PopupMenu add(String label, Runnable action) {
        return add(new MenuItem(label, action));
    }

    public PopupMenu addSeparator() {
        return add(MenuItem.separator());
    }

    /**
     * The live item list, which can be edited freely between shows.
     */
    public List<MenuItem> getItems() {
        return items;
    }

    public void clear() {
        items.clear();
    }

    /**
     * Runs just before every show, so items can be enabled/disabled or rebuilt to match current state.
     */
    public void onOpen(Consumer<PopupMenu> onOpen) {
        this.onOpen = onOpen;
    }

    /**
     * Runs whenever the menu closes, by any route (choice, outside click, Escape).
     */
    public void onClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /* Appearance */

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
    }

    public float getItemHeight() {
        return itemHeight;
    }

    public void setItemHeight(float itemHeight) {
        this.itemHeight = Math.max(1f, itemHeight);
    }

    public float getMinWidth() {
        return minWidth;
    }

    public void setMinWidth(float minWidth) {
        this.minWidth = minWidth;
    }

    /**
     * Pins the panel background instead of following the theme; null goes back to the theme.
     */
    public void setBackgroundColor(Paint color) {
        this.backgroundColor = color;
    }

    /**
     * Pins the highlight behind the hovered/active row; null goes back to the theme.
     */
    public void setHoverColor(Paint color) {
        this.hoverColor = color;
    }

    public void setTextColor(Paint color) {
        this.textColor = color;
    }

    public void setDisabledTextColor(Paint color) {
        this.disabledTextColor = color;
    }

    public void setBorderColor(Paint color) {
        this.borderColor = color;
    }

    /**
     * Moves the animated paints on; the open popup calls it every frame.
     */
    void advance(float delta) {
        for (Paint paint : new Paint[]{backgroundColor, hoverColor, textColor, disabledTextColor, borderColor}) {
            AnimatedPaint.advanceIfAnimated(paint, delta);
        }
    }

    Paint background() {
        return backgroundColor != null ? backgroundColor : ThemeManager.getCurrent().getFieldBackground();
    }

    Paint highlight() {
        return hoverColor != null ? hoverColor : ThemeManager.getCurrent().getSelection();
    }

    Paint text() {
        return textColor != null ? textColor : ThemeManager.getCurrent().getText();
    }

    Paint disabledText() {
        return disabledTextColor != null ? disabledTextColor : ThemeManager.getCurrent().getPlaceholderText();
    }

    Paint border() {
        return borderColor != null ? borderColor : ThemeManager.getCurrent().getFieldBorder();
    }

    Theme theme() {
        return ThemeManager.getCurrent();
    }

    /* Showing */

    public boolean isOpen() {
        return popup != null;
    }

    /**
     * Opens the menu with its top-left corner at ({@code x}, {@code y}) in window coordinates,
     * pulled back inside the window if it would overflow the right or bottom edge. Replaces
     * any popup already open. Does nothing with no items or no window.
     */
    public void show(Window window, double x, double y) {
        show(window, x, y, null);
    }

    /**
     * As {@link #show(Window, double, double)}, on {@code anchor}'s window, but the menu then
     * follows {@code anchor} (see {@link Window#showPopup(me.piitex.engine.ui.containers.Container,
     * Element, Runnable)}): it moves along when the anchor moves, and closes when the anchor is
     * removed, disabled or scrolled out of view. The right-click context menu opens this way.
     */
    public void show(Element anchor, double x, double y) {
        show(anchor.getWindow(), x, y, anchor);
    }

    private void show(Window window, double x, double y, Element anchor) {
        if (window == null) return;
        if (onOpen != null) onOpen.accept(this); // may populate an empty menu, so run before the emptiness check
        if (items.isEmpty()) return;

        MenuPopup p = new MenuPopup(this, window, x, y);
        window.showPopup(p, anchor, () -> {
            p.dispose();
            if (popup == p) popup = null;
            if (closeListener != null) closeListener.run();
            if (onClose != null) onClose.run();
        });
        popup = p; // after showPopup, whose closePopup() of a previous menu would otherwise clear this
    }

    /**
     * Opens the menu where the mouse pointer currently is, which is what a right-click handler needs.
     */
    public void showAtMouse(Window window) {
        show(window, MouseTracker.getX(), MouseTracker.getY());
    }

    /**
     * Opens the menu just under {@code anchor} with their left edges aligned, as for a "more" button.
     * It follows {@code anchor} while open, as {@link #show(Element, double, double)} does.
     */
    public void showBelow(Element anchor) {
        show(anchor, anchor.getAbsoluteX(), anchor.getAbsoluteY() + anchor.getHeight());
    }

    /**
     * Closes the menu (and any open submenus) if it is showing.
     */
    public void close() {
        MenuPopup p = popup;
        if (p == null) return;
        p.close();
    }
}
