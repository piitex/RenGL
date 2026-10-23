package me.piitex.engine.ui.layout;

import me.piitex.engine.ui.Element;

/**
 * Layers its children on top of one another in the same area instead of beside each
 * other. The first child added is drawn at the back and each later one over it (see
 * {@link #getElements()}). This suits a badge on an icon, a {@link
 * me.piitex.engine.ui.overlays.LoadingSpinnerOverlay} over the content it's waiting on,
 * or text centered on an image.
 * <p>
 * Each child is placed independently within this Layout's padded bounds per {@link
 * #getAlignment()}, defaulting to {@code TOP_LEFT}. {@link #setChildAlignment} overrides
 * that for a single child, e.g. to pin a badge to the top-right corner while the
 * rest stay centered. {@link #getSpacing()} has no effect here.
 */
public class StackLayout extends Layout {
    private final java.util.Map<Element, Alignment> childAlignments = new java.util.HashMap<>();

    public StackLayout(double width, double height) {
        this(0, 0, width, height);
    }

    public StackLayout(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    /**
     * Overrides the placement of one child; null clears the override so it follows the layout's own alignment again.
     */
    public void setChildAlignment(Element child, Alignment alignment) {
        if (alignment == null) childAlignments.remove(child);
        else childAlignments.put(child, alignment);
    }

    /**
     * The widest child's width, since children overlap.
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
        double left = getPadding();
        double top = getPadding();
        double innerWidth = getWidth() - 2 * getPadding();
        double innerHeight = getHeight() - 2 * getPadding();
        for (Element child : getElements().values()) {
            Alignment a = childAlignments.getOrDefault(child, getAlignment());
            child.setX(left + (innerWidth - child.getWidth()) * a.horizontal());
            child.setY(top + (innerHeight - child.getHeight()) * a.vertical());
        }
    }
}
