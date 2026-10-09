package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.image.IconCache;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A scrolling table: one row per item and one {@link Column column} per property, with a header row that stays at the
 * top. The user clicks a header to sort by that column and drags the edge between two headers to resize a column.
 * Think of an archive's listing (Name, Size, Packed, Modified) or a file browser's details view.
 * <pre>{@code
 * TableView<Entry> table = new TableView<>(600, 400);
 * table.addColumn("Name", Entry::name).setWidth(260);
 * table.addColumn("Size", e -> formatSize(e.size())).setAlignment(TextAlign.RIGHT).setComparator(Comparator.comparingLong(Entry::size));
 * table.addColumn("Modified", e -> e.modified().toString()).setComparator(Comparator.comparing(Entry::modified));
 * table.setItems(entries);
 * table.onActivate(entry -> open(entry));
 * }</pre>
 * The table does not draw the items itself beyond asking each column for the cell's text, so a table of thousands of
 * rows costs no more than the rows on screen. Sorting is done here, on a copy of the list that
 * {@link #setItems} was given; {@link #getItems()} is the order shown. After changing the model, call {@link #refresh()}.
 * The selection is kept by the items' {@code equals}.
 * <p>
 * The user can select a row by clicking it or with the arrow keys, select several with {@link #setMultiSelect}, and
 * open one with a double-click or Enter ({@link #onActivate}). A {@link #setContextMenu context menu} opens on a
 * right-click after selecting the row under the pointer (unless it is already part of the selection). When the
 * columns are wider than the table it scrolls sideways, and the header scrolls with the cells.
 * <p>
 * It follows the theme like a {@link Container} that {@link #useTheme() uses the theme}. It registers its own
 * {@link #onMouseClick} handler, so do not replace it.
 */
public class TableView<T> extends ScrollContainer implements Focusable, FontScalable {
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final double DRAG_THRESHOLD = 4;
    private static final float CELL_PADDING = 8f;
    private static final float SORT_ARROW = 9f;
    private static final double RESIZE_GRAB = 4; // either side of a header's edge

    /**
     * One column: a header title, the text each row shows in it, and how the column sorts and resizes. The setters
     * return the column, so a column can be set up where it is added.
     */
    public static final class Column<T> {
        private final String title;
        private final Function<T, String> text;
        private float width = 120f, minWidth = 40f;
        private TextAlign alignment = TextAlign.LEFT;
        private Comparator<? super T> comparator;
        private boolean sortable = true, resizable = true;
        private Function<T, IconAsset> icon;
        private Function<T, Color> iconColor;

        private Column(String title, Function<T, String> text) {
            this.title = title;
            this.text = text;
        }

        public String getTitle() {
            return title;
        }

        public float getWidth() {
            return width;
        }

        /**
         * The column's width in points, at least its {@link #setMinWidth minimum}. Default 120.
         */
        public Column<T> setWidth(float width) {
            this.width = Math.max(minWidth, width);
            return this;
        }

        public float getMinWidth() {
            return minWidth;
        }

        /**
         * The narrowest the user can make the column. Default 40.
         */
        public Column<T> setMinWidth(float minWidth) {
            this.minWidth = Math.max(8f, minWidth);
            this.width = Math.max(this.minWidth, width);
            return this;
        }

        public TextAlign getAlignment() {
            return alignment;
        }

        /**
         * Where the text sits in its cell: {@code LEFT} by default, {@code RIGHT} for numbers. The header follows.
         */
        public Column<T> setAlignment(TextAlign alignment) {
            this.alignment = alignment == null ? TextAlign.LEFT : alignment;
            return this;
        }

        /**
         * How a click on the header orders the rows. Without one the cell text is compared, ignoring case and
         * treating runs of digits as numbers ({@code file2} before {@code file10}). Give one for a column whose text
         * does not sort like its value, such as sizes written as "1.5 MB" or dates.
         */
        public Column<T> setComparator(Comparator<? super T> comparator) {
            this.comparator = comparator;
            return this;
        }

        public boolean isSortable() {
            return sortable;
        }

        /**
         * Whether clicking the header sorts by this column. On by default.
         */
        public Column<T> setSortable(boolean sortable) {
            this.sortable = sortable;
            return this;
        }

        public boolean isResizable() {
            return resizable;
        }

        /**
         * Whether the user can drag the header's right edge to change the width. On by default.
         */
        public Column<T> setResizable(boolean resizable) {
            this.resizable = resizable;
            return this;
        }

        /**
         * An icon drawn before the text in each cell of this column, or null for none on that row. For the Name
         * column of a file listing.
         */
        public Column<T> setIconProvider(Function<T, IconAsset> icon) {
            this.icon = icon;
            return this;
        }

        /**
         * The color each row's icon in this column is drawn in. Null, or a null color for a row, draws it in the text
         * color (a {@link #setDimmed dimmed} row's icon is faint either way). Lets a folder be accented.
         */
        public Column<T> setIconColorProvider(Function<T, Color> iconColor) {
            this.iconColor = iconColor;
            return this;
        }

        private String textOf(T item) {
            String value = text.apply(item);
            return value == null ? "" : value;
        }
    }

    /**
     * A key press or repeat on the focused table, offered before the table's own keys. Return true to use it up.
     */
    @FunctionalInterface
    public interface KeyListener {
        boolean onKey(int key, int mods);
    }

    private final List<Column<T>> columns = new ArrayList<>();
    private final List<T> items = new ArrayList<>(); // as shown: sorted
    private Collection<? extends T> source = List.of();  // as given; read again by refresh()
    private Column<T> sortColumn;
    private boolean sortAscending = true;

    private T selected;
    private final Set<T> selection = new LinkedHashSet<>();
    private T anchor;
    private boolean multiSelect;

    private Predicate<T> dimmed = item -> false;
    private Predicate<T> sortGroup;
    private Consumer<T> onSelect;
    private Consumer<List<T>> onSelectionChange;
    private Consumer<T> onActivate;
    private BiConsumer<Column<T>, Boolean> onSort;
    private KeyListener keyListener;

    private float rowHeight = 24f;
    private float headerHeight = 28f;
    private float fontSize = 15f;
    private float iconSize = 14f;
    private float rowCornerRadius;
    private boolean striped;
    private final Overridable<Paint> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Paint> dimTextColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> headerTextColor = new Overridable<>(Color.BLACK);
    private final Overridable<Paint> selectionColor = new Overridable<>(new Color(0.60f, 0.78f, 1.0f, 0.55f));

    private org.jetbrains.skia.Font font;
    private Typeface fontTypeface;
    private boolean focused;

    // A press: on a header it sorts on release or resizes once the pointer moves; on a row it already selected.
    private boolean draggingThumb;
    private Column<T> pressedHeader, resizing;
    private double pressX, resizeStartX;
    private float resizeStartWidth;
    private boolean headerMoved;
    private T lastPressItem;
    private long lastPressNanos;

    /**
     * A table at (0, 0) with no columns. Add them with {@link #addColumn}.
     */
    public TableView(double width, double height) {
        super(width, height);
        setHorizontalScrollEnabled(true);
        setFocusTraversable(true);
        onMouseClick(event -> {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
            int index = rowAt(event.getMouseY());
            T item = index >= 0 ? items.get(index) : null;
            if (multiSelect && item != null && selection.contains(item)) {
                if (!Objects.equals(selected, item)) {
                    selected = item;
                    if (onSelect != null) onSelect.accept(item);
                }
                return;
            }
            if (headerAt(event.getMouseY())) return;
            selectByUser(item);
        });
        ThemeManager.register(this);
    }

    /* Columns */

    /**
     * Adds a column on the right. {@code text} gives a row's text in it (null is shown as empty).
     */
    public Column<T> addColumn(String title, Function<T, String> text) {
        Column<T> column = new Column<>(title, Objects.requireNonNull(text, "text"));
        columns.add(column);
        clampScroll();
        return column;
    }

    public void removeColumn(Column<T> column) {
        if (!columns.remove(column)) return;
        if (column == sortColumn) sortColumn = null;
        clampScroll();
    }

    /**
     * The columns, left to right.
     */
    public List<Column<T>> getColumns() {
        return List.copyOf(columns);
    }

    /* Model */

    /**
     * The rows. The collection is kept, not copied, and read again by every {@link #refresh()}, so a live list stays
     * current with a refresh after each change. The table sorts a copy, so the collection is never reordered.
     * <p>
     * New rows start at the top: the table scrolls back to the first row, as when a folder is opened. {@link #refresh()}
     * keeps the scroll position instead.
     */
    public void setItems(Collection<? extends T> items) {
        this.source = items == null ? List.of() : items;
        refresh();
        scrollTo(getScrollOffsetX(), 0);
    }

    /**
     * Reads the items given to {@link #setItems} again: call it after items were added, removed or changed (a text a column shows is read every
     * frame, so a change that does not move rows needs no refresh). Keeps the sort, the selection and the scroll position (clamped if the table got shorter); a selected
     * item that is gone is unselected, without {@link #onSelect}.
     */
    public void refresh() {
        items.clear();
        items.addAll(source);
        applySort();
        pruneSelection();
        clampScroll();
    }

    /**
     * The items in the order shown now, top to bottom.
     */
    public List<T> getItems() {
        return List.copyOf(items);
    }

    /**
     * Items drawn in the dim text color. None by default.
     */
    public void setDimmed(Predicate<T> dimmed) {
        this.dimmed = dimmed == null ? item -> false : dimmed;
    }

    /* Sorting */

    /**
     * Sorts by {@code column}, without {@link #onSort}. Rows that compare equal keep their order.
     */
    public void sortBy(Column<T> column, boolean ascending) {
        sortColumn = column;
        sortAscending = ascending;
        applySort();
    }

    /**
     * Goes back to the order {@link #setItems} was given.
     */
    public void clearSort() {
        sortColumn = null;
        sortAscending = true;
        applySort();
    }

    public Column<T> getSortColumn() {
        return sortColumn;
    }

    public boolean isSortAscending() {
        return sortAscending;
    }

    /**
     * Fired with the column and whether it is ascending when the user clicks a header. The table has already sorted;
     * use it to remember the order or to show it elsewhere.
     */
    public void onSort(BiConsumer<Column<T>, Boolean> onSort) {
        this.onSort = onSort;
    }

    /**
     * Rows this matches stay above the others whatever the sort column and direction, each group sorted on its own:
     * folders first in a file listing, also when the Name column is sorted descending. Null, the default, is no
     * grouping. Without a sort the groups still apply and each keeps the order {@link #setItems} gave.
     */
    public void setSortGroup(Predicate<T> sortGroup) {
        this.sortGroup = sortGroup;
        applySort();
    }

    private void applySort() {
        items.clear();
        items.addAll(source);
        Comparator<T> order = null;
        if (sortColumn != null) {
            Column<T> column = sortColumn;
            Comparator<? super T> by = column.comparator != null
                    ? column.comparator
                    : Comparator.comparing((T item) -> column.textOf(item), TableView::compareNatural);
            order = sortAscending ? by::compare : by.reversed()::compare;
        }
        if (sortGroup != null) {
            Predicate<T> group = sortGroup;
            Comparator<T> grouped = Comparator.comparing((T item) -> !group.test(item)); // false (matching) first
            order = order == null ? grouped : grouped.thenComparing(order);
        }
        if (order != null) items.sort(order);
    }

    /**
     * Compares ignoring case, with a run of digits counting as the number it spells.
     */
    static int compareNatural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (si < a.length() && a.charAt(si) == '0') si++;
                while (sj < b.length() && b.charAt(sj) == '0') sj++;
                int ei = si, ej = sj;
                while (ei < a.length() && Character.isDigit(a.charAt(ei))) ei++;
                while (ej < b.length() && Character.isDigit(b.charAt(ej))) ej++;
                if (ei - si != ej - sj) return Integer.compare(ei - si, ej - sj);
                int digits = a.substring(si, ei).compareTo(b.substring(sj, ej));
                if (digits != 0) return digits;
                i = ei;
                j = ej;
                continue;
            }
            int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (c != 0) return c;
            i++;
            j++;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private void sortByUser(Column<T> column) {
        if (!column.sortable) return;
        T lead = selected;
        sortAscending = column == sortColumn ? !sortAscending : true;
        sortColumn = column;
        applySort();
        if (lead != null) scrollToRow(items.indexOf(lead));
        if (onSort != null) onSort.accept(column, sortAscending);
    }

    /* Selection */

    /**
     * The selected item, or null. With {@link #setMultiSelect multi-select} it is the lead of the selection: the row
     * clicked or moved to last, which the arrow keys move on from.
     */
    public T getSelected() {
        return selected;
    }

    /**
     * Selects only {@code item} and scrolls to it, or clears the selection with null or an item that is not in the
     * table. Does not fire {@link #onSelect}.
     */
    public void setSelected(T item) {
        int index = indexOf(item);
        if (index < 0) {
            clearSelection();
            return;
        }
        selectOnly(item);
        scrollToRow(index);
    }

    /**
     * Whether the user can select several rows. Off by default. On, a click selects one row, Cmd-click (Ctrl-click off
     * macOS) adds or removes a row, Shift-click selects the rows from the last clicked one to this one, Shift with the
     * arrow keys, Home and End does the same from the keyboard, and Cmd/Ctrl+A selects every row.
     * Turning it off keeps only the lead item selected.
     */
    public void setMultiSelect(boolean multiSelect) {
        this.multiSelect = multiSelect;
        if (!multiSelect && selection.size() > 1) {
            if (selected != null) selectOnly(selected);
            else clearSelection();
        }
    }

    public boolean isMultiSelect() {
        return multiSelect;
    }

    /**
     * Every selected item, top to bottom.
     */
    public List<T> getSelectedItems() {
        List<T> result = new ArrayList<>(selection.size());
        for (T item : items) {
            if (selection.contains(item)) result.add(item);
        }
        return result;
    }

    public boolean isSelected(T item) {
        return item != null && selection.contains(item);
    }

    /**
     * Selects exactly these items. Items that are not in the table are skipped. Without
     * {@link #setMultiSelect multi-select} only the last one is kept. Does not fire the selection listeners.
     */
    public void setSelectedItems(Collection<? extends T> chosen) {
        List<T> found = new ArrayList<>();
        for (T item : chosen) {
            if (indexOf(item) >= 0) found.add(item);
        }
        clearSelection();
        if (found.isEmpty()) return;
        if (!multiSelect) found = List.of(found.getLast());
        selection.addAll(found);
        selected = found.getLast();
        anchor = found.getFirst();
    }

    /**
     * Selects every row, when {@link #setMultiSelect multi-select} is on. Silent.
     */
    public void selectAll() {
        if (!multiSelect || items.isEmpty()) return;
        selection.clear();
        selection.addAll(items);
        selected = items.getLast();
        anchor = items.getFirst();
    }

    /**
     * Clears the selection. Silent.
     */
    public void clearSelection() {
        selection.clear();
        selected = anchor = null;
    }

    /**
     * Fired with the new lead item (null when cleared) when the user changes which row has the keyboard.
     * Not fired by the setters or {@link #refresh}.
     */
    public void onSelect(Consumer<T> onSelect) {
        this.onSelect = onSelect;
    }

    /**
     * Fired with every selected item, top to bottom, when the user changes the selection. Not fired by the setters
     * or {@link #refresh}.
     */
    public void onSelectionChange(Consumer<List<T>> onSelectionChange) {
        this.onSelectionChange = onSelectionChange;
    }

    /**
     * Fired when the user double-clicks a row or presses Enter on the lead row.
     */
    public void onActivate(Consumer<T> onActivate) {
        this.onActivate = onActivate;
    }

    private void selectByUser(T item) {
        if (item == null ? selection.isEmpty() : selection.size() == 1 && Objects.equals(item, selected)) {
            anchor = item;
            return;
        }
        T before = selected;
        if (item == null) clearSelection();
        else selectOnly(item);
        notifySelection(before);
    }

    private void selectOnly(T item) {
        selection.clear();
        selection.add(item);
        selected = anchor = item;
    }

    private void notifySelection(T leadBefore) {
        if (onSelect != null && !Objects.equals(selected, leadBefore)) onSelect.accept(selected);
        if (onSelectionChange != null) onSelectionChange.accept(getSelectedItems());
    }

    private void pruneSelection() {
        if (selection.isEmpty()) {
            selected = anchor = null;
            return;
        }
        Set<T> present = new HashSet<>(items);
        selection.retainAll(present);
        if (selected != null && !selection.contains(selected)) selected = null;
        if (anchor != null && !present.contains(anchor)) anchor = null;
        if (selected == null && !selection.isEmpty()) selected = selection.iterator().next();
        if (selection.isEmpty()) selected = anchor = null;
    }

    private void selectRange(int from, int to, boolean keep) {
        if (!keep) selection.clear();
        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) selection.add(items.get(i));
    }

    /**
     * The modifier keys held now, as GLFW bits. A press carries none, so the table asks the window's input.
     */
    protected int heldModifiers() {
        Window window = getWindow();
        return window == null ? 0 : window.getInput().getModifiers();
    }

    /* Keys */

    /**
     * Offered every key press and repeat while the table has the keyboard, before the table's own keys (the arrows,
     * Home, End, Page Up and Down, Enter). Use it for keys like Delete.
     */
    public void onKey(KeyListener keyListener) {
        this.keyListener = keyListener;
    }

    /**
     * Whether the table has the keyboard.
     */
    public boolean isFocused() {
        return focused;
    }

    @Override
    public void onFocusGained() {
        focused = true;
    }

    @Override
    public void onFocusLost() {
        focused = false;
    }

    @Override
    public void onCharTyped(int codepoint) {
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action == GLFW.GLFW_RELEASE) return;
        if (keyListener != null && keyListener.onKey(key, mods)) return;
        if (items.isEmpty()) return;
        boolean shift = multiSelect && (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (multiSelect && key == GLFW.GLFW_KEY_A && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0) {
            T before = selected;
            selectAll();
            notifySelection(before);
            return;
        }
        int index = indexOf(selected);
        switch (key) {
            case GLFW.GLFW_KEY_UP -> index = index < 0 ? items.size() - 1 : Math.max(0, index - 1);
            case GLFW.GLFW_KEY_DOWN -> index = Math.min(items.size() - 1, index + 1);
            case GLFW.GLFW_KEY_HOME -> index = 0;
            case GLFW.GLFW_KEY_END -> index = items.size() - 1;
            case GLFW.GLFW_KEY_PAGE_UP -> index = Math.max(0, index - pageRows());
            case GLFW.GLFW_KEY_PAGE_DOWN -> index = Math.min(items.size() - 1, Math.max(0, index) + pageRows());
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (index >= 0 && onActivate != null && action == GLFW.GLFW_PRESS) onActivate.accept(items.get(index));
                return;
            }
            default -> {
                return;
            }
        }
        int anchorRow = shift ? indexOf(anchor) : -1;
        if (anchorRow >= 0) {
            T before = selected;
            selectRange(anchorRow, index, false);
            selected = items.get(index);
            notifySelection(before);
        } else {
            selectByUser(items.get(index));
        }
        scrollToRow(index);
    }

    private int pageRows() {
        return Math.max(1, (int) ((getHeight() - headerHeight) / rowHeight) - 1);
    }

    /* Mouse */

    /**
     * A press anywhere on the table is the table's: it selects a row, sorts or resizes a column, or drags a scrollbar.
     */
    @Override
    public boolean canDrag(double x, double y) {
        return true;
    }

    @Override
    public CursorMode getCursorMode() {
        if (resizing != null) return CursorMode.RESIZE_HORIZONTAL;
        if (MouseTracker.isInWindow() && !draggingThumb && edgeAt(MouseTracker.getX(), MouseTracker.getY()) != null) {
            return CursorMode.RESIZE_HORIZONTAL;
        }
        return super.getCursorMode();
    }

    @Override
    public void onDragStart(double x, double y) {
        if (thumbAt(x, y)) {
            draggingThumb = true;
            super.onDragStart(x, y);
            return;
        }
        pressedHeader = resizing = null;
        headerMoved = false;
        if (headerAt(y)) {
            Column<T> edge = edgeAt(x, y);
            if (edge != null) {
                resizing = edge;
                resizeStartX = x;
                resizeStartWidth = edge.width;
            } else {
                pressedHeader = columnAt(x);
                pressX = x;
            }
            return;
        }
        int index = rowAt(y);
        if (index < 0) {
            selectByUser(null);
            return;
        }
        T item = items.get(index);
        int mods = multiSelect ? heldModifiers() : 0;
        boolean toggle = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0, range = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (toggle || range) {
            lastPressItem = null; // a modifier click is not half of a double-click
            T before = selected;
            int anchorRow = indexOf(anchor);
            if (range && anchorRow >= 0) {
                selectRange(anchorRow, index, toggle);
                selected = item;
            } else if (range) {
                selectOnly(item);
            } else if (selection.add(item)) {
                selected = anchor = item;
            } else {
                selection.remove(item);
                anchor = item;
                if (Objects.equals(selected, item)) selected = selection.isEmpty() ? null : selection.stream().reduce((a, b) -> b).orElse(null);
            }
            notifySelection(before);
            return;
        }
        long now = System.nanoTime();
        boolean doubleClick = Objects.equals(item, lastPressItem) && now - lastPressNanos <= DOUBLE_CLICK_NANOS;
        lastPressItem = item;
        lastPressNanos = doubleClick ? 0 : now; // a third press starts a new pair
        selectByUser(item);
        if (doubleClick && onActivate != null) onActivate.accept(item);
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (draggingThumb) {
            super.onDragUpdate(x, y);
            return;
        }
        if (resizing != null) {
            resizing.setWidth((float) (resizeStartWidth + x - resizeStartX));
            clampScroll();
        } else if (pressedHeader != null && Math.abs(x - pressX) >= DRAG_THRESHOLD) {
            headerMoved = true;
        }
    }

    @Override
    public void onDragEnd(double x, double y) {
        if (draggingThumb) {
            draggingThumb = false;
            super.onDragEnd(x, y);
            return;
        }
        Column<T> clicked = pressedHeader;
        pressedHeader = resizing = null;
        if (clicked != null && !headerMoved && clicked == columnAt(x) && headerAt(y)) sortByUser(clicked);
    }

    /* Geometry */

    /**
     * Whether a window y is in the header.
     */
    private boolean headerAt(double windowY) {
        double y = toLocalY(windowY) - getY();
        return y >= 0 && y < headerHeight;
    }

    /**
     * The row under a window y, or -1 (the header counts as no row).
     */
    private int rowAt(double windowY) {
        double viewportY = toLocalY(windowY) - getY();
        if (viewportY < headerHeight) return -1;
        int index = (int) ((viewportY + getScrollOffsetY() - headerHeight) / rowHeight);
        return index >= 0 && index < items.size() ? index : -1;
    }

    private int indexOf(T item) {
        if (item == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (Objects.equals(items.get(i), item)) return i;
        }
        return -1;
    }

    /**
     * The width column {@code index} is drawn at: its own, except the last one stretches to fill the table when the
     * columns are narrower than it.
     */
    private float widthOf(int index) {
        Column<T> column = columns.get(index);
        if (index < columns.size() - 1) return column.width;
        return Math.max(column.width, (float) getWidth() - leftOf(index));
    }

    private float leftOf(int index) {
        float left = 0f;
        for (int i = 0; i < index; i++) left += columns.get(i).width;
        return left;
    }

    private float totalWidth() {
        float total = 0f;
        for (Column<T> column : columns) total += column.width;
        return total;
    }

    /**
     * The column under a window x (any y), or null past the last one.
     */
    private Column<T> columnAt(double windowX) {
        double contentX = toLocalX(windowX) - getX() + getScrollOffsetX();
        for (int i = 0; i < columns.size(); i++) {
            float left = leftOf(i);
            if (contentX >= left && contentX < left + widthOf(i)) return columns.get(i);
        }
        return null;
    }

    /**
     * The column whose right edge is under the window point, if the point is in the header and that column can be
     * resized.
     */
    private Column<T> edgeAt(double windowX, double windowY) {
        if (!headerAt(windowY)) return null;
        double contentX = toLocalX(windowX) - getX() + getScrollOffsetX();
        for (int i = 0; i < columns.size(); i++) {
            double edge = leftOf(i) + widthOf(i);
            if (Math.abs(contentX - edge) <= RESIZE_GRAB && columns.get(i).resizable) return columns.get(i);
        }
        return null;
    }

    private void scrollToRow(int index) {
        if (index < 0) return;
        double top = headerHeight + index * rowHeight, bottom = top + rowHeight, offset = getScrollOffsetY();
        if (top < offset + headerHeight) {
            scrollTo(getScrollOffsetX(), top - headerHeight);
        } else if (bottom > offset + getHeight()) {
            scrollTo(getScrollOffsetX(), bottom - getHeight());
        }
    }

    private void clampScroll() {
        scrollTo(getScrollOffsetX(), getScrollOffsetY());
    }

    /**
     * The rows and header decide how far the table scrolls.
     */
    @Override
    public double getMaxScrollY() {
        return Math.max(0, headerHeight + items.size() * rowHeight - getHeight());
    }

    @Override
    public double getMaxScrollX() {
        return Math.max(0, totalWidth() - getWidth());
    }

    /* Look */

    public float getRowHeight() {
        return rowHeight;
    }

    /**
     * The height of each row. Default 24.
     */
    public void setRowHeight(float rowHeight) {
        this.rowHeight = Math.max(1f, rowHeight);
        clampScroll();
    }

    public float getHeaderHeight() {
        return headerHeight;
    }

    /**
     * The height of the header row. Default 28.
     */
    public void setHeaderHeight(float headerHeight) {
        this.headerHeight = Math.max(1f, headerHeight);
        clampScroll();
    }

    public float getIconSize() {
        return iconSize;
    }

    /**
     * The width and height of a column's {@link Column#setIconProvider icons}. Default 14.
     */
    public void setIconSize(float iconSize) {
        this.iconSize = Math.max(1f, iconSize);
    }

    public float getRowCornerRadius() {
        return rowCornerRadius;
    }

    /**
     * How rounded the selected and hovered rows' highlights are. Default 0, square.
     */
    public void setRowCornerRadius(float rowCornerRadius) {
        this.rowCornerRadius = Math.max(0f, rowCornerRadius);
    }

    public boolean isStriped() {
        return striped;
    }

    /**
     * Whether every other row is drawn slightly darker, to help the eye follow a row across. Off by default.
     */
    public void setStriped(boolean striped) {
        this.striped = striped;
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        closeFont();
    }

    @Override
    public float[] getFontSizes() {
        return new float[]{fontSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        setFontSize(sizes[0]);
    }

    public Paint getTextColor() {
        return textColor.get();
    }

    public Paint getDimTextColor() {
        return dimTextColor.get();
    }

    public Paint getSelectionColor() {
        return selectionColor.get();
    }

    /**
     * Pins the text color (and the header's) against theme switches.
     */
    public void setTextColor(Paint color) {
        textColor.set(color);
    }

    /**
     * The color of the column titles: the text color unless {@link #setHeaderTextColor} pinned another.
     */
    public Paint getHeaderTextColor() {
        return headerTextColor.get();
    }

    /**
     * Pins the color of the column titles against theme switches, for a quieter header than the rows (for example
     * {@link #getDimTextColor()}). Null goes back to following the theme's text color.
     */
    public void setHeaderTextColor(Paint color) {
        if (color == null) {
            headerTextColor.clearExplicit();
            return;
        }
        headerTextColor.set(color);
    }

    /**
     * Pins the color of {@link #setDimmed dimmed} items.
     */
    public void setDimTextColor(Paint color) {
        dimTextColor.set(color);
    }

    /**
     * Pins the selected rows' color.
     */
    public void setSelectionColor(Paint color) {
        selectionColor.set(color);
    }

    @Override
    public void applyTheme(Theme theme) {
        super.applyTheme(theme);
        textColor.applyTheme(theme.getText());
        headerTextColor.applyTheme(theme.getText());
        dimTextColor.applyTheme(theme.getPlaceholderText());
        selectionColor.applyTheme(theme.getAccentTint());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        textColor.clearExplicit();
        headerTextColor.clearExplicit();
        dimTextColor.clearExplicit();
        selectionColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(headerTextColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(dimTextColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(selectionColor.get(), delta);
    }

    private org.jetbrains.skia.Font getFont() {
        Typeface typeface = Font.getDefault();
        if (font == null || typeface != fontTypeface) {
            closeFont();
            font = new org.jetbrains.skia.Font(typeface, fontSize);
            fontTypeface = typeface;
        }
        return font;
    }

    private void closeFont() {
        if (font != null) {
            font.close();
            font = null;
        }
    }

    /**
     * Frees the font. The table can still be drawn afterwards; it makes a new one.
     */
    public void dispose() {
        closeFont();
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        renderBackground(renderer);
        pushChildClip(renderer);
        renderRows(renderer);
        renderHeader(renderer);
        renderChildren(renderer);
        renderer.popClip();
        if (isScrollbarVisible()) renderScrollbars(renderer);
    }

    private void renderRows(Renderer renderer) {
        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        float scrollY = (float) getScrollOffsetY(), scrollX = (float) getScrollOffsetX();
        org.jetbrains.skia.Font f = getFont();
        float textH = FontMetricsCompat.of(f).getHeight();
        Paint text = textColor.get();
        Paint selectionPaint = selectionColor.get();
        Color iconTint = solid(text);

        int hoverRow = -1;
        double mx = MouseTracker.getX(), my = MouseTracker.getY();
        if (!draggingThumb && resizing == null && isHovered() && MouseTracker.isInWindow() && contains(mx, my) && !thumbAt(mx, my)) {
            hoverRow = rowAt(my);
        }

        renderer.pushClip(x, y + headerHeight, w, h - headerHeight);
        int first = Math.max(0, (int) (scrollY / rowHeight));
        for (int i = first; i < items.size(); i++) {
            float rowY = y + headerHeight + i * rowHeight - scrollY;
            if (rowY >= y + h) break;
            T item = items.get(i);
            if (striped && i % 2 == 1) {
                renderer.pushAlpha(0.05f);
                renderer.drawQuad(x, rowY, w, rowHeight, text, 0f);
                renderer.popAlpha();
            }
            if (selection.contains(item)) {
                if (!focused) renderer.pushAlpha(0.6f);
                renderer.drawQuad(x, rowY, w, rowHeight, selectionPaint, rowCornerRadius);
                if (!focused) renderer.popAlpha();
            } else if (i == hoverRow) {
                renderer.pushAlpha(0.07f);
                renderer.drawQuad(x, rowY, w, rowHeight, text, rowCornerRadius);
                renderer.popAlpha();
            }
            boolean dim = dimmed.test(item);
            Paint color = dim ? dimTextColor.get() : text;
            for (int c = 0; c < columns.size(); c++) {
                Column<T> column = columns.get(c);
                float cellX = x + leftOf(c) - scrollX, cellW = widthOf(c);
                if (cellX + cellW <= x || cellX >= x + w) continue;
                drawCell(renderer, column, item, cellX, rowY, cellW, textH, f, color, iconTint, dim);
            }
        }
        renderer.popClip();
    }

    private void drawCell(Renderer renderer, Column<T> column, T item, float cellX, float rowY, float cellW,
                          float textH, org.jetbrains.skia.Font f, Paint color, Color iconTint, boolean dim) {
        float left = cellX + CELL_PADDING, right = cellX + cellW - CELL_PADDING;
        IconAsset icon = column.icon == null ? null : column.icon.apply(item);
        if (icon != null) {
            int size = Math.round(iconSize);
            Color tint = column.iconColor == null ? null : column.iconColor.apply(item);
            renderer.drawImage(IconCache.loadResource(icon.resourcePath(), size, size), left,
                    rowY + (rowHeight - size) / 2f, size, size, 0f, dim ? 0.45f : 1f, tint == null ? iconTint : tint);
        }
        if (column.icon != null) left += iconSize + 6f;
        String text = column.textOf(item);
        if (text.isEmpty() || right <= left) return;
        float textW = f.measureTextWidth(text);
        boolean clip = textW > right - left;
        float textX = switch (column.alignment) {
            case RIGHT -> Math.max(left, right - textW);
            case CENTER -> Math.max(left, (left + right - textW) / 2f);
            default -> left;
        };
        if (clip) renderer.pushClip(left, rowY, right - left, rowHeight);
        renderer.drawText(text, textX, rowY + (rowHeight - textH) / 2f, f, color);
        if (clip) renderer.popClip();
    }

    private void renderHeader(Renderer renderer) {
        float x = (float) getX(), y = (float) getY(), w = (float) getWidth();
        float scrollX = (float) getScrollOffsetX();
        org.jetbrains.skia.Font f = getFont();
        float textH = FontMetricsCompat.of(f).getHeight();
        Paint text = textColor.get();
        Paint titles = headerTextColor.get();

        renderer.pushAlpha(0.08f);
        renderer.drawQuad(x, y, w, headerHeight, text, 0f);
        renderer.popAlpha();
        renderer.pushAlpha(0.25f);
        renderer.drawLine(x, y + headerHeight - 0.5f, x + w, y + headerHeight - 0.5f, text, 1f);
        renderer.popAlpha();

        for (int c = 0; c < columns.size(); c++) {
            Column<T> column = columns.get(c);
            float cellX = x + leftOf(c) - scrollX, cellW = widthOf(c);
            if (cellX + cellW <= x || cellX >= x + w) continue;
            float left = cellX + CELL_PADDING, right = cellX + cellW - CELL_PADDING;
            boolean sorted = column == sortColumn;
            if (sorted) right -= SORT_ARROW + 4f;
            if (right > left) {
                float textW = f.measureTextWidth(column.title);
                boolean clip = textW > right - left;
                float textX = switch (column.alignment) {
                    case RIGHT -> Math.max(left, right - textW);
                    case CENTER -> Math.max(left, (left + right - textW) / 2f);
                    default -> left;
                };
                if (clip) renderer.pushClip(left, y, right - left, headerHeight);
                renderer.drawText(column.title, textX, y + (headerHeight - textH) / 2f, f, titles);
                if (clip) renderer.popClip();
            }
            if (sorted) drawSortArrow(renderer, cellX + cellW - CELL_PADDING - SORT_ARROW / 2f, y + headerHeight / 2f, sortAscending, titles);
            if (c < columns.size() - 1 || cellX + cellW < x + w) {
                renderer.pushAlpha(0.25f);
                renderer.drawLine(cellX + cellW - 0.5f, y + 5f, cellX + cellW - 0.5f, y + headerHeight - 5f, text, 1f);
                renderer.popAlpha();
            }
        }
    }

    private static void drawSortArrow(Renderer renderer, float cx, float cy, boolean ascending, Paint color) {
        float r = 3.5f;
        if (ascending) {
            renderer.drawLine(cx - r, cy + r / 2f, cx, cy - r / 2f, color, 1.5f);
            renderer.drawLine(cx, cy - r / 2f, cx + r, cy + r / 2f, color, 1.5f);
        } else {
            renderer.drawLine(cx - r, cy - r / 2f, cx, cy + r / 2f, color, 1.5f);
            renderer.drawLine(cx, cy + r / 2f, cx + r, cy - r / 2f, color, 1.5f);
        }
    }

    private static Color solid(Paint paint) {
        return switch (AnimatedPaint.resolve(paint)) {
            case Color c -> c;
            case LinearGradient g -> g.getColors().get(0);
            case null, default -> Color.BLACK;
        };
    }
}
