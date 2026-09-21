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

    @Override
    protected void layoutChildren() {
        double contentHeight = -getSpacing();
        for (Element child : getElements().values()) {
            contentHeight += child.getHeight() + getSpacing();
        }
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
}
