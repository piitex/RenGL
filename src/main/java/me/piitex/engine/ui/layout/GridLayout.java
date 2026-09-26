package me.piitex.engine.ui.layout;

import me.piitex.engine.ui.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Arranges its children in a table: left-to-right across {@link #getColumns()}
 * columns, wrapping to a new row after each full one, in child order (see {@link
 * #getElements()}). Each column is as wide as its widest child and each row as tall
 * as its tallest, so mixed-size children still line up in clean columns. The usual use
 * is a form of label and field pairs with {@code setColumns(2)}.
 * <p>
 * A child smaller than its cell is placed within it per {@link #getAlignment()}
 * (default {@code TOP_LEFT}). {@link #setSpacing} sets the gap between columns and rows together, {@link
 * #setColumnSpacing}/{@link #setRowSpacing} set them separately. {@link #getPadding()}
 * insets the whole grid from this Layout's edges.
 */
public class GridLayout extends Layout {
    private int columns;
    private float columnSpacing;
    private float rowSpacing;

    public GridLayout(int columns, double width, double height) {
        this(columns, 0, 0, width, height);
    }

    public GridLayout(int columns, double x, double y, double width, double height) {
        super(x, y, width, height);
        this.columns = Math.max(1, columns);
    }

    public int getColumns() {
        return columns;
    }

    /**
     * How many children go in each row; values below 1 are treated as 1.
     */
    public void setColumns(int columns) {
        this.columns = Math.max(1, columns);
        relayout();
    }

    /**
     * Sets both the column gap and the row gap.
     */
    @Override
    public void setSpacing(float spacing) {
        super.setSpacing(spacing);
        this.columnSpacing = spacing;
        this.rowSpacing = spacing;
    }

    /**
     * Gap, in logical points, between one column and the next.
     */
    public float getColumnSpacing() {
        return columnSpacing;
    }

    public void setColumnSpacing(float columnSpacing) {
        this.columnSpacing = columnSpacing;
    }

    /**
     * Gap, in logical points, between one row and the next.
     */
    public float getRowSpacing() {
        return rowSpacing;
    }

    public void setRowSpacing(float rowSpacing) {
        this.rowSpacing = rowSpacing;
    }

    /**
     * Does nothing: child order is the order children are placed cell by cell, so moving one to the front would move
     * it to the end. Children do not overlap, so front and back make no difference.
     */
    @Override
    protected void moveChildToFront(Element child) {
    }

    /**
     * The column widths plus the column spacing between them, counting only columns that hold a child.
     */
    @Override
    public double getContentWidth() {
        if (getElements().isEmpty()) return 0;
        double width = -columnSpacing;
        for (double columnWidth : columnWidths(new ArrayList<>(getElements().values()))) {
            width += columnWidth + columnSpacing;
        }
        return width;
    }

    /**
     * The row heights plus the row spacing between them.
     */
    @Override
    public double getContentHeight() {
        if (getElements().isEmpty()) return 0;
        double height = -rowSpacing;
        for (double rowHeight : rowHeights(new ArrayList<>(getElements().values()))) {
            height += rowHeight + rowSpacing;
        }
        return height;
    }

    /**
     * Each used column's width: its widest child. Fewer than {@link #getColumns()} entries when there are fewer children than columns.
     */
    private double[] columnWidths(List<Element> children) {
        double[] widths = new double[Math.min(columns, children.size())];
        for (int i = 0; i < children.size(); i++) {
            widths[i % columns] = Math.max(widths[i % columns], children.get(i).getWidth());
        }
        return widths;
    }

    /**
     * Each row's height: its tallest child.
     */
    private double[] rowHeights(List<Element> children) {
        double[] heights = new double[(children.size() + columns - 1) / columns];
        for (int i = 0; i < children.size(); i++) {
            heights[i / columns] = Math.max(heights[i / columns], children.get(i).getHeight());
        }
        return heights;
    }

    @Override
    protected void layoutChildren() {
        List<Element> children = new ArrayList<>(getElements().values());
        int rows = (children.size() + columns - 1) / columns;
        double[] columnWidths = columnWidths(children);
        double[] rowHeights = rowHeights(children);

        double top = getPadding();
        for (int row = 0; row < rows; row++) {
            double left = getPadding();
            for (int col = 0; col < columnWidths.length; col++) {
                int index = row * columns + col;
                if (index < children.size()) {
                    Element child = children.get(index);
                    child.setX(left + (columnWidths[col] - child.getWidth()) * getAlignment().horizontal());
                    child.setY(top + (rowHeights[row] - child.getHeight()) * getAlignment().vertical());
                }
                left += columnWidths[col] + columnSpacing;
            }
            top += rowHeights[row] + rowSpacing;
        }
    }
}
