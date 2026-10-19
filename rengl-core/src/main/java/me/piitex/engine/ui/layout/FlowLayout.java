package me.piitex.engine.ui.layout;

import me.piitex.engine.ui.Element;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.List;

/**
 * Arranges its children left to right in rows, starting a new row when the next child wouldn't fit in the width that is
 * left, in child order (see {@link #getElements()}). Tags, chips, thumbnails and toolbars that must wrap are the usual
 * use. Children keep their own sizes.
 * <p>
 * {@link #getSpacing()} is the gap between children in a row and {@link #getLineSpacing()} the gap between rows;
 * {@link #getPadding()} insets everything from this layout's edges. {@link #getAlignment()} places each row within the
 * layout's width by its horizontal part and the whole block of rows by its vertical part. Within a row, children sit at
 * the top.
 * <p>
 * A flow that is {@link #setSizeToContent sized to its content} in width doesn't wrap, since there is no width to wrap
 * at: it is a single row. Size it to content in height only, which is what a flow inside a
 * {@link me.piitex.engine.ui.scroll.ScrollContainer} wants.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public class FlowLayout extends Layout {
    private float lineSpacing;

    public FlowLayout(double width, double height) {
        this(0, 0, width, height);
    }

    public FlowLayout(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    /**
     * Gap, in logical points, between one row and the next. The gap between children in a row is {@link #getSpacing()}.
     */
    public float getLineSpacing() {
        return lineSpacing;
    }

    public void setLineSpacing(float lineSpacing) {
        this.lineSpacing = lineSpacing;
    }

    @Override
    protected void scaleSpacing(double sx, double sy) {
        setSpacing((float) (getSpacing() * sx));
        lineSpacing *= (float) sy;
    }

    /**
     * Does nothing: child order is the order children are placed, so moving one to the front would move it to the end.
     * Children do not overlap, so front and back make no difference.
     */
    @Override
    protected void moveChildToFront(Element child) {
    }

    /**
     * The width of the widest row.
     */
    @Override
    public double getContentWidth() {
        double width = 0;
        for (List<Element> row : rows()) width = Math.max(width, rowWidth(row));
        return width;
    }

    /**
     * Every row's height plus the line spacing between them.
     */
    @Override
    public double getContentHeight() {
        List<List<Element>> rows = rows();
        if (rows.isEmpty()) return 0;
        double height = -lineSpacing;
        for (List<Element> row : rows) height += rowHeight(row) + lineSpacing;
        return height;
    }

    /**
     * The children split into rows for the width there is now.
     */
    private List<List<Element>> rows() {
        double limit = isSizeToContentWidth() ? Double.MAX_VALUE : Math.max(0, getWidth() - 2 * getPadding());
        List<List<Element>> rows = new ArrayList<>();
        List<Element> row = new ArrayList<>();
        double used = 0;
        for (Element child : getElements().values()) {
            double needed = row.isEmpty() ? child.getWidth() : used + getSpacing() + child.getWidth();
            if (!row.isEmpty() && needed > limit) {
                rows.add(row);
                row = new ArrayList<>();
                needed = child.getWidth();
            }
            row.add(child);
            used = needed;
        }
        if (!row.isEmpty()) rows.add(row);
        return rows;
    }

    private double rowWidth(List<Element> row) {
        double width = -getSpacing();
        for (Element child : row) width += child.getWidth() + getSpacing();
        return Math.max(0, width);
    }

    private static double rowHeight(List<Element> row) {
        double height = 0;
        for (Element child : row) height = Math.max(height, child.getHeight());
        return height;
    }

    @Override
    protected void layoutChildren() {
        List<List<Element>> rows = rows();
        double innerWidth = getWidth() - 2 * getPadding();
        double innerHeight = getHeight() - 2 * getPadding();
        double y = getPadding() + Math.max(0, innerHeight - getContentHeight()) * getAlignment().vertical();
        for (List<Element> row : rows) {
            double x = getPadding() + Math.max(0, innerWidth - rowWidth(row)) * getAlignment().horizontal();
            for (Element child : row) {
                child.setX(x);
                child.setY(y);
                x += child.getWidth() + getSpacing();
            }
            y += rowHeight(row) + lineSpacing;
        }
    }
}
