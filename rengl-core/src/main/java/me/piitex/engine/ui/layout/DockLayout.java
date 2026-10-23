package me.piitex.engine.ui.layout;

import me.piitex.engine.ui.Element;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Pins each child to an edge of the space that is left, in child order, and gives the last {@link Dock#FILL} child
 * whatever remains. A header across the top, a status bar along the bottom, a side panel and a content area is four
 * children, in that order, docked {@code TOP}, {@code BOTTOM}, {@code LEFT} and {@code FILL}.
 * <p>
 * A child docked to the top or bottom keeps its own height and is stretched to the width that is left; one docked to a
 * side keeps its width and takes the height that is left; a fill child takes both. The layout sets those sizes, so
 * setting them on the child has no lasting effect. Children are docked {@link Dock#TOP} unless told otherwise with
 * {@link #setDock} or {@link #addElement(Element, Dock)}.
 * <p>
 * {@link #getSpacing()} is the gap between docked children and {@link #getPadding()} insets them all from the layout's
 * edges. {@link #getAlignment()} isn't used. A child after a fill child has nowhere to go and gets no space.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public class DockLayout extends Layout {
    /**
     * The edge of the remaining space a child is pinned to.
     */
    public enum Dock {
        TOP, BOTTOM, LEFT, RIGHT, FILL
    }

    private final Map<Element, Dock> docks = new WeakHashMap<>();

    public DockLayout(double width, double height) {
        this(0, 0, width, height);
    }

    public DockLayout(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    /**
     * Where {@code child} is docked: {@link Dock#TOP} if it was never set.
     */
    public Dock getDock(Element child) {
        return docks.getOrDefault(child, Dock.TOP);
    }

    /**
     * Docks {@code child}, which needn't be in this layout yet, to {@code dock}. Null is {@link Dock#TOP}.
     */
    public void setDock(Element child, Dock dock) {
        docks.put(child, dock == null ? Dock.TOP : dock);
        relayout();
    }

    /**
     * Adds {@code child}, docked to {@code dock}, after the ones already there.
     */
    public void addElement(Element child, Dock dock) {
        docks.put(child, dock == null ? Dock.TOP : dock);
        addElement(child);
    }

    /**
     * Does nothing: child order is the order the edges are claimed in, so moving one to the front would change the
     * layout. Children do not overlap, so front and back make no difference.
     */
    @Override
    protected void moveChildToFront(Element child) {
    }

    /**
     * The width the children need: what the side ones add up to, or the widest of the rest, whichever the order of
     * docking makes larger.
     */
    @Override
    public double getContentWidth() {
        return need(true);
    }

    @Override
    public double getContentHeight() {
        return need(false);
    }

    /**
     * The size along one axis the children need, from the last docked inwards: what is docked along that axis adds, the
     * rest asks for its own size.
     */
    private double need(boolean horizontal) {
        Element[] children = getElements().values().toArray(Element[]::new);
        double need = 0;
        boolean any = false;
        for (int i = children.length - 1; i >= 0; i--) {
            Element child = children[i];
            Dock dock = getDock(child);
            boolean along = horizontal ? dock == Dock.LEFT || dock == Dock.RIGHT : dock == Dock.TOP || dock == Dock.BOTTOM;
            double size = horizontal ? child.getWidth() : child.getHeight();
            if (along) {
                need += size + (any ? getSpacing() : 0);
            } else {
                need = Math.max(need, size);
            }
            any = true;
        }
        return need;
    }

    @Override
    protected void layoutChildren() {
        double left = getPadding(), top = getPadding();
        double right = getWidth() - getPadding(), bottom = getHeight() - getPadding();
        for (Element child : getElements().values()) {
            double width = Math.max(0, right - left), height = Math.max(0, bottom - top);
            switch (getDock(child)) {
                case TOP -> {
                    child.setBounds(left, top, width, Math.min(child.getHeight(), height));
                    top += child.getHeight() + getSpacing();
                }
                case BOTTOM -> {
                    double h = Math.min(child.getHeight(), height);
                    child.setBounds(left, bottom - h, width, h);
                    bottom -= h + getSpacing();
                }
                case LEFT -> {
                    child.setBounds(left, top, Math.min(child.getWidth(), width), height);
                    left += child.getWidth() + getSpacing();
                }
                case RIGHT -> {
                    double w = Math.min(child.getWidth(), width);
                    child.setBounds(right - w, top, w, height);
                    right -= w + getSpacing();
                }
                case FILL -> {
                    child.setBounds(left, top, width, height);
                    left = right;
                    top = bottom;
                }
            }
        }
    }
}
