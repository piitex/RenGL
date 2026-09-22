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
     * Where the children sit within this Layout. See {@link Alignment}. {@code TOP_LEFT} by default.
     */
    public Alignment getAlignment() {
        return alignment;
    }

    public void setAlignment(Alignment alignment) {
        this.alignment = alignment == null ? Alignment.TOP_LEFT : alignment;
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
     * {@link HorizontalLayout} nested in a {@link VerticalLayout}. That child's own
     * children were positioned relative to where it sat before this call moved it.
     * Without the nested pass they would stay stale until that Layout next updated on
     * its own, which happens every frame once the engine is running, but would be wrong
     * if read immediately after building the tree.
     */
    public void relayout() {
        layoutChildren();
        for (Element child : getElements().values()) {
            if (child instanceof Layout nestedLayout) {
                nestedLayout.relayout();
            }
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
