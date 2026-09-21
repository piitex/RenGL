package me.piitex.engine.ui.layout;

import me.piitex.engine.ui.Element;

/**
 * Arranges its children in a single left-to-right row, each one spaced {@link
 * #getSpacing()} apart from the next, inset from this Layout's own edges by {@link
 * #getPadding()}. {@link #getAlignment()} places the row within the layout (e.g. {@code
 * CENTER_LEFT}, {@code CENTER}, {@code BOTTOM_RIGHT}): its horizontal part places the
 * whole row, its vertical part positions each child within it. Their horizontal order
 * always follows child order (see {@link #getElements()}).
 */
public class HorizontalLayout extends Layout {

    public HorizontalLayout(double width, double height) {
        this(0, 0, width, height);
    }

    public HorizontalLayout(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    @Override
    protected void layoutChildren() {
        double contentWidth = -getSpacing();
        for (Element child : getElements().values()) {
            contentWidth += child.getWidth() + getSpacing();
        }
        double innerWidth = getWidth() - 2 * getPadding();
        double cursorX = getPadding() + Math.max(0, innerWidth - contentWidth) * getAlignment().horizontal();

        double top = getPadding();
        double innerHeight = getHeight() - 2 * getPadding();
        for (Element child : getElements().values()) {
            child.setX(cursorX);
            child.setY(top + (innerHeight - child.getHeight()) * getAlignment().vertical());
            cursorX += child.getWidth() + getSpacing();
        }
    }
}
