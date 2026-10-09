package me.piitex.engine.ui.layout;

import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.Element;

/**
 * A {@link Container} that positions its own children automatically instead of
 * relying on each one's x and y being set by hand. {@link HorizontalLayout} arranges
 * them left to right, and {@link VerticalLayout} top to bottom. Everything else about a
 * Container, including styling, the click, render, and update hooks, and transitions,
 * works the same way; a Layout is a Container, not a separate concept.
 * <p>
 * Positioning happens automatically: once immediately whenever {@link #addElement} is
 * called, and once every frame in {@link #update}, which covers a child that was resized
 * or added some other way. There is normally nothing to call directly. Use
 * {@link #relayout()} after mutating a child's size directly, when the new position has
 * to take effect before the next frame.
 * <p>
 * By default a Layout keeps the size it was given. {@link #setSizeToContent} makes it
 * grow or shrink to fit its children instead, which is what a Layout inside a {@link
 * me.piitex.engine.ui.scroll.ScrollContainer} needs for the scroll range to match its content.
 */
public abstract class Layout extends Container {

    /**
     * Where a Layout's children sit within it, as one of nine named positions. For a
     * {@link VerticalLayout} or {@link HorizontalLayout} it places the whole block of
     * children in the layout's padded area. For a {@link GridLayout} it places each
     * child within its cell, and for a {@link StackLayout} it places each child within
     * the layout.
     */
    public enum Alignment {
        TOP_LEFT(0f, 0f), TOP_CENTER(0.5f, 0f), TOP_RIGHT(1f, 0f),
        CENTER_LEFT(0f, 0.5f), CENTER(0.5f, 0.5f), CENTER_RIGHT(1f, 0.5f),
        BOTTOM_LEFT(0f, 1f), BOTTOM_CENTER(0.5f, 1f), BOTTOM_RIGHT(1f, 1f);

        private final float horizontal;
        private final float vertical;

        Alignment(float horizontal, float vertical) {
            this.horizontal = horizontal;
            this.vertical = vertical;
        }

        /**
         * Fraction of the free horizontal space placed before the content: 0 = left, 0.5 = centered, 1 = right.
         */
        public float horizontal() {
            return horizontal;
        }

        /**
         * Fraction of the free vertical space placed above the content: 0 = top, 0.5 = centered, 1 = bottom.
         */
        public float vertical() {
            return vertical;
        }
    }

    private float spacing = 0f;
    private float padding = 0f;
    private Alignment alignment = Alignment.TOP_LEFT;
    private boolean sizeToContentWidth;
    private boolean sizeToContentHeight;

    protected Layout(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    /**
     * Gap, in logical points, left between consecutive children along the axis this Layout arranges them.
     */
    public float getSpacing() {
        return spacing;
    }

    public void setSpacing(float spacing) {
        this.spacing = spacing;
    }

    /**
     * Inset, in logical points, kept between this Layout's own edges and its children.
     */
    public float getPadding() {
        return padding;
    }

    public void setPadding(float padding) {
        this.padding = padding;
    }

    /**
     * Scales the padding and the gaps by the factors a resizing {@link me.piitex.engine.ui.containers.ResizableContainer}
     * scaled its sizes by, {@code sx} across and {@code sy} down. The padding, which is the same on every side, goes
     * by the geometric mean of the two, so scaling there and back returns it to what it was. Not meant to be called
     * directly.
     */
    public void scaleGaps(double sx, double sy) {
        setPadding((float) (getPadding() * Math.sqrt(sx * sy)));
        scaleSpacing(sx, sy);
    }

    /**
     * Scales the gap between children. The gap in a layout that arranges them along one axis goes by that axis's
     * factor; in one that uses it both ways, by the mean of the two.
     */
    protected void scaleSpacing(double sx, double sy) {
        setSpacing((float) (getSpacing() * Math.sqrt(sx * sy)));
    }

    /**
     * Where the children sit within this Layout. See {@link Alignment}. {@code TOP_LEFT} by default.
     */
    public Alignment getAlignment() {
        return alignment;
    }

    public void setAlignment(Alignment alignment) {
        this.alignment = alignment == null ? Alignment.TOP_LEFT : alignment;
    }

    /**
     * Makes this Layout size itself to its children on both axes, or go back to the size it was given. See {@link #setSizeToContent(boolean, boolean)}.
     */
    public void setSizeToContent(boolean sizeToContent) {
        setSizeToContent(sizeToContent, sizeToContent);
    }

    /**
     * Makes this Layout's width and/or height follow its children: {@link #getContentWidth()} or {@link
     * #getContentHeight()} plus the padding on both sides, kept up to date every frame as children are added,
     * removed or resized. An axis left false keeps whatever size was last set. The usual case is a {@link
     * VerticalLayout} of a fixed width inside a scroll container, with {@code setSizeToContent(false, true)}.
     * <p>
     * Nested Layouts that also size to content are measured first, so a column of rows sizes correctly in one
     * pass. A parent that sizes its own children, such as a {@link me.piitex.engine.ui.containers.SplitContainer}
     * pane, overrides this. Off on both axes by default.
     */
    public void setSizeToContent(boolean width, boolean height) {
        this.sizeToContentWidth = width;
        this.sizeToContentHeight = height;
        relayout();
    }

    public boolean isSizeToContentWidth() {
        return sizeToContentWidth;
    }

    public boolean isSizeToContentHeight() {
        return sizeToContentHeight;
    }

    /**
     * The width the children need, spacing included but not padding, from their current sizes. A Layout that
     * sizes its children to itself, such as a {@link me.piitex.engine.ui.containers.SplitContainer}, has no
     * such width and returns its own inner width.
     */
    public double getContentWidth() {
        return Math.max(0, getWidth() - 2 * getPadding());
    }

    /**
     * The height counterpart of {@link #getContentWidth()}.
     */
    public double getContentHeight() {
        return Math.max(0, getHeight() - 2 * getPadding());
    }

    /**
     * Repositions every child per this Layout's rules, in child order (see {@link #getElements()}).
     */
    protected abstract void layoutChildren();

    /**
     * Recomputes every child's position right now, rather than waiting for the next
     * automatic pass in {@link #update}. Useful right after resizing a child directly.
     * <p>
     * This also relays out any child that is itself a Layout, such as a
     * {@link HorizontalLayout} nested in a {@link VerticalLayout}. Without the nested
     * pass it would stay stale until that Layout next updated on its own, which happens
     * every frame once the engine is running, but would be wrong if read immediately
     * after building the tree.
     * <p>
     * It runs in two passes: sizes to content bottom-up, since a Layout's size depends on
     * its children's, then positions top-down, since a Layout like {@link
     * me.piitex.engine.ui.containers.SplitContainer} sizes its children to itself.
     */
    public void relayout() {
        fitToContent();
        arrange();
    }

    private void fitToContent() {
        for (Element child : getElements().values()) {
            if (child instanceof Layout nestedLayout) {
                nestedLayout.fitToContent();
            }
        }
        if (sizeToContentWidth) setWidth(getContentWidth() + 2 * getPadding());
        if (sizeToContentHeight) setHeight(getContentHeight() + 2 * getPadding());
    }

    private void arrange() {
        double innerWidth = Math.max(0, getWidth() - 2 * getPadding()), innerHeight = Math.max(0, getHeight() - 2 * getPadding());
        for (Element child : getElements().values()) {
            child.applyRelativeSize(innerWidth, innerHeight);
        }
        layoutChildren();
        for (Element child : getElements().values()) {
            if (child instanceof Layout nestedLayout) {
                nestedLayout.arrange();
            }
        }
    }

    /**
     * Gives the children with a {@link Element#setLayoutWeight weight} their share of the space along one axis that the
     * others and the spacing leave. For {@link HorizontalLayout} and {@link VerticalLayout}. Does nothing when this
     * layout sizes itself to its content along that axis.
     */
    protected void shareSpace(boolean horizontal) {
        if (horizontal ? sizeToContentWidth : sizeToContentHeight) return;
        double totalWeight = 0, fixed = Math.max(0, getElements().size() - 1) * (double) getSpacing();
        for (Element child : getElements().values()) {
            if (child.getLayoutWeight() > 0) totalWeight += child.getLayoutWeight();
            else fixed += horizontal ? child.getWidth() : child.getHeight();
        }
        if (totalWeight <= 0) return;
        double leftover = Math.max(0, (horizontal ? getWidth() : getHeight()) - 2 * getPadding() - fixed);
        for (Element child : getElements().values()) {
            if (child.getLayoutWeight() <= 0) continue;
            double size = leftover * child.getLayoutWeight() / totalWeight;
            if (horizontal) child.setWidth(size); else child.setHeight(size);
        }
    }

    @Override
    public void addElement(Element element) {
        super.addElement(element);
        relayout();
    }

    @Override
    public void addElement(Element element, int index) {
        super.addElement(element, index);
        relayout();
    }

    @Override
    public boolean removeElement(Element element) {
        boolean removed = super.removeElement(element);
        if (removed) relayout();
        return removed;
    }

    @Override
    public Element removeElement(int index) {
        Element removed = super.removeElement(index);
        if (removed != null) relayout();
        return removed;
    }

    @Override
    public void update(float delta) {
        relayout();
        super.update(delta);
    }
}
