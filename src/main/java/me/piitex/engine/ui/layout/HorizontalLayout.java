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

    /**
     * Does nothing: child order is the order children are placed left to right, so moving one to the front would move
     * it to the end. Children do not overlap, so front and back make no difference.
     */
    @Override
    protected void moveChildToFront(Element child) {
    }

    /**
     * Every child's width plus the spacing between them.
     */
    @Override
    public double getContentWidth() {
        if (getElements().isEmpty()) return 0;
        double width = -getSpacing();
        for (Element child : getElements().values()) {
            width += child.getWidth() + getSpacing();
        }
        return width;
    }

    /**
     * The tallest child's height.
     */
    @Override
    public double getContentHeight() {
        double height = 0;
        for (Element child : getElements().values()) {
            height = Math.max(height, child.getHeight());
        }
        return height;
    }

    @Override
    protected void layoutChildren() {
        double contentWidth = getContentWidth();
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
