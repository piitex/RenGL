package me.piitex.engine.ui.layout;

import me.piitex.engine.ui.Element;

/**
 * Arranges its children in a single top-to-bottom column, each one spaced {@link
 * #getSpacing()} apart from the next, inset from this Layout's own edges by {@link
 * #getPadding()}. {@link #getAlignment()} places the column within the layout (e.g.
 * {@code TOP_CENTER}, {@code CENTER}, {@code BOTTOM_RIGHT}): its vertical part places
 * the whole column, its horizontal part positions each child within it. Their vertical
 * order always follows child order (see {@link #getElements()}).
 */
public class VerticalLayout extends Layout {

    public VerticalLayout(double width, double height) {
        super(0, 0, width, height);
    }


    public VerticalLayout(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    /**
     * Does nothing: child order is the order children are placed top to bottom, so moving one to the front would move
     * it to the end. Children do not overlap, so front and back make no difference.
     */
    @Override
    protected void moveChildToFront(Element child) {
    }

    /**
     * The widest child's width.
     */
    @Override
    public double getContentWidth() {
        double width = 0;
        for (Element child : getElements().values()) {
            width = Math.max(width, child.getWidth());
        }
        return width;
    }

    /**
     * Every child's height plus the spacing between them.
     */
    @Override
    public double getContentHeight() {
        if (getElements().isEmpty()) return 0;
        double height = -getSpacing();
        for (Element child : getElements().values()) {
            height += child.getHeight() + getSpacing();
        }
        return height;
    }

    @Override
    protected void layoutChildren() {
        shareSpace(false);
        double contentHeight = getContentHeight();
        double innerHeight = getHeight() - 2 * getPadding();
        double cursorY = getPadding() + Math.max(0, innerHeight - contentHeight) * getAlignment().vertical();

        double left = getPadding();
        double innerWidth = getWidth() - 2 * getPadding();
        for (Element child : getElements().values()) {
            child.setX(left + (innerWidth - child.getWidth()) * getAlignment().horizontal());
            child.setY(cursorY);
            cursorY += child.getHeight() + getSpacing();
        }
    }

    @Override
    protected void scaleSpacing(double sx, double sy) {
        setSpacing((float) (getSpacing() * sy));
    }
}
