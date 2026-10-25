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
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.text.TextFit;
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
 * Rows can have {@link #setCheckBoxes check boxes} for choosing items apart from selecting them, and a column that is
 * {@linkplain Column#setEditable editable} turns into a text field when its cell is double-clicked or F2 is pressed.
 * <p>
 * It follows the theme like a {@link Container} that {@link #useTheme() uses the theme}. Its own right-click
 * selection runs before any {@link #onMouseClick} handler you set.
 */
public class TableView<T> extends ScrollContainer implements Focusable, FontScalable {
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final double DRAG_THRESHOLD = 4;
    private static final float CELL_PADDING = 8f;
    private static final float SORT_ARROW = 9f;
    private static final double RESIZE_GRAB = 4; // either side of a header's edge
    private static final float CHECK_COLUMN = 30f; // the width of the column of check boxes
    private static final float BOX_SIZE = 14f; // a check box

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
        private boolean sortable = true, resizable = true, ellipsize = true, ellipsisInMiddle;
        private Function<T, IconAsset> icon;
        private Function<T, Color> iconColor;
        private BiConsumer<T, String> onEdit;
        private Function<T, String> editText;

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

        public boolean isEllipsized() {
            return ellipsize;
        }

        /**
         * Whether text too long for the cell is ended with an ellipsis ({@code …}). On by default; off cuts it at the
         * cell's edge.
         */
        public Column<T> setEllipsized(boolean ellipsize) {
            this.ellipsize = ellipsize;
            return this;
        }

        public boolean isEllipsisInMiddle() {
            return ellipsisInMiddle;
        }

        /**
         * Puts the ellipsis in the middle of text that does not fit, so the end stays: a file name keeps its extension.
         * Off by default.
         */
        public Column<T> setEllipsisInMiddle(boolean ellipsisInMiddle) {
            this.ellipsisInMiddle = ellipsisInMiddle;
            return this;
        }

        public boolean isEditable() {
            return onEdit != null;
        }

        /**
         * Lets the user edit this column's cells in place: a double-click on a cell, or F2 on the selected row, puts a text
         * field over it. Enter, or a click elsewhere, ends the edit and calls {@code onEdit} with the row's item and the new
         * text (if it changed); Escape cancels. The table does not change the model: update the item, and call
         * {@link TableView#refresh()} if the sort should follow. Null, the default, makes the column read-only.
         */
        public Column<T> setEditable(BiConsumer<T, String> onEdit) {
            this.onEdit = onEdit;
            return this;
        }

        /**
         * The text the field starts with, for a column whose cell shows something other than what is typed: a size written
         * as "1.5 MB" that is edited as {@code 1572864}. Null, the default, starts with the text the cell shows.
         */
        public Column<T> setEditText(Function<T, String> editText) {
            this.editText = editText;
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

    private boolean checkBoxes;
    private final Set<T> checked = new LinkedHashSet<>();
    private BiConsumer<T, Boolean> onCheck;

    private TextFieldOverlay editField;
    private T editing;
    private Column<T> editingColumn;
    private String editOriginal;

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
        setBuiltInClickListener(event -> {
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
        if (column == editingColumn) finishEdit(false, false);
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
        finishEdit(false, false);
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
        pruneChecked();
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

    /* Check boxes */

    /**
     * Whether each row has a check box in a column of its own before the others, and the header a box that ticks or clears all of
     * them, for choosing items apart from selecting them (the files to extract, the rows to delete). Off by default. A click
     * on a box, or Space on the lead row, ticks it; the ticked items are {@link #getCheckedItems()}. Ticking never changes the
     * selection.
     */
    public void setCheckBoxes(boolean checkBoxes) {
        this.checkBoxes = checkBoxes;
        finishEdit(false, false);
        clampScroll();
    }

    public boolean isCheckBoxes() {
        return checkBoxes;
    }

    public boolean isChecked(T item) {
        return item != null && checked.contains(item);
    }

    /**
     * Every ticked item, top to bottom. Ticks are kept by the items' {@code equals}, and {@link #refresh()} forgets the ones whose
     * item is gone.
     */
    public List<T> getCheckedItems() {
        List<T> result = new ArrayList<>(checked.size());
        for (T item : items) {
            if (checked.contains(item)) result.add(item);
        }
        return result;
    }

    /**
     * Ticks or clears one item. An item that is not in the table is ignored. Does not fire {@link #onCheck}.
     */
    public void setChecked(T item, boolean on) {
        if (item == null) return;
        if (!on) checked.remove(item);
        else if (indexOf(item) >= 0) checked.add(item);
    }

    /**
     * Ticks exactly these items (those not in the table are skipped) and clears the rest. Silent.
     */
    public void setCheckedItems(Collection<? extends T> chosen) {
        checked.clear();
        for (T item : chosen) setChecked(item, true);
    }

    /**
     * Ticks every row. Silent.
     */
    public void checkAll() {
        checked.addAll(items);
    }

    /**
     * Clears every tick. Silent.
     */
    public void clearChecked() {
        checked.clear();
    }

    /**
     * Fired with the item and whether it is now ticked when the user ticks or clears a box. The header's box fires it for each
     * row it changes.
     */
    public void onCheck(BiConsumer<T, Boolean> onCheck) {
        this.onCheck = onCheck;
    }

    private void toggleCheck(T item) {
        boolean on = !checked.contains(item);
        setChecked(item, on);
        if (onCheck != null) onCheck.accept(item, on);
    }

    /** The header's box: clears everything if every row is ticked, else ticks every row. */
    private void toggleAllChecks() {
        boolean all = !items.isEmpty() && checked.size() >= items.size();
        for (T item : new ArrayList<>(items)) {
            if (checked.contains(item) == !all) continue;
            setChecked(item, !all);
            if (onCheck != null) onCheck.accept(item, !all);
        }
    }

    private void pruneChecked() {
        if (!checked.isEmpty()) checked.retainAll(new HashSet<>(items));
    }

    /* Editing */

    /**
     * Puts a text field over the cell of {@code item} in {@code column}, with its text selected, for the user to type a new one.
     * Does nothing unless the column is {@linkplain Column#setEditable editable} and the item is in the table. An edit already
     * in progress is ended first, keeping what was typed.
     */
    public void startEdit(T item, Column<T> column) {
        finishEdit(true, false);
        if (column == null || column.onEdit == null || !columns.contains(column)) return;
        int index = indexOf(item);
        if (index < 0) return;
        selectByUser(item);
        scrollToRow(index);
        scrollToColumn(column);
        editing = item;
        editingColumn = column;
        String start = column.editText != null ? column.editText.apply(item) : column.textOf(item);
        editOriginal = start == null ? "" : start;
        editField = new TextFieldOverlay(0, 0) {
            @Override
            public void onKeyPressed(int key, int action, int mods) {
                if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
                    finishEdit(false, true);
                    return;
                }
                super.onKeyPressed(key, action, mods);
            }
        };
        editField.setFontSize(fontSize);
        editField.setPadding(CELL_PADDING - 2f);
        editField.setRightAligned(column.alignment == TextAlign.RIGHT);
        editField.setText(editOriginal);
        editField.selectAll();
        editField.onSubmit(text -> finishEdit(true, true));
        editField.onFocusChanged(gained -> {
            if (!gained) finishEdit(true, false);
        });
        placeEditor();
        addElement(editField);
        Window window = getWindow();
        if (window != null) window.requestFocus(editField);
    }

    /**
     * Whether a cell is being edited.
     */
    public boolean isEditing() {
        return editField != null;
    }

    /**
     * The item whose cell is being edited, or null.
     */
    public T getEditingItem() {
        return editing;
    }

    /**
     * Ends the edit in progress and reports the text, as Enter does. Nothing happens if no cell is being edited.
     */
    public void commitEdit() {
        finishEdit(true, true);
    }

    /**
     * Ends the edit in progress and throws the text away, as Escape does.
     */
    public void cancelEdit() {
        finishEdit(false, true);
    }

    /** The text field over the cell, for tests. */
    TextFieldOverlay getEditor() {
        return editField;
    }

    /**
     * Ends an edit: {@code commit} reports the text if it changed. Enter and Escape give the keyboard back to the table; a click
     * elsewhere has already moved it, so {@code refocus} is false then.
     */
    private void finishEdit(boolean commit, boolean refocus) {
        TextFieldOverlay field = editField;
        if (field == null) return;
        T item = editing;
        Column<T> column = editingColumn;
        editField = null;
        editing = null;
        editingColumn = null;
        removeElement(field);
        Window window = getWindow();
        if (refocus && window != null) window.requestFocus(this);
        String text = field.getText();
        field.dispose();
        if (commit && column.onEdit != null && !text.equals(editOriginal)) column.onEdit.accept(item, text);
    }

    private void placeEditor() {
        int index = indexOf(editing), c = columns.indexOf(editingColumn);
        if (index < 0 || c < 0) {
            finishEdit(false, false);
            return;
        }
        editField.setBounds(leftOf(c), headerHeight + index * rowHeight, widthOf(c), rowHeight);
    }

    private void scrollToColumn(Column<T> column) {
        int c = columns.indexOf(column);
        if (c < 0) return;
        float left = leftOf(c), right = left + widthOf(c);
        double offset = getScrollOffsetX();
        if (left < offset) scrollTo(left, getScrollOffsetY());
        else if (right > offset + getWidth()) scrollTo(right - getWidth(), getScrollOffsetY());
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
            case GLFW.GLFW_KEY_SPACE -> {
                if (index >= 0 && checkBoxes && action == GLFW.GLFW_PRESS) toggleCheck(items.get(index));
                return;
            }
            case GLFW.GLFW_KEY_F2 -> {
                if (index < 0 || action != GLFW.GLFW_PRESS) return;
                for (Column<T> column : columns) {
                    if (column.onEdit != null) {
                        startEdit(items.get(index), column);
                        break;
                    }
                }
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
            if (checkBoxes && contentX(x) < CHECK_COLUMN) {
                toggleAllChecks();
                return;
            }
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
        if (checkBoxes && contentX(x) < CHECK_COLUMN) {
            lastPressItem = null;
            toggleCheck(item);
            return;
        }
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
        if (doubleClick) {
            Column<T> cell = columnAt(x);
            if (cell != null && cell.onEdit != null) startEdit(item, cell);
            else if (onActivate != null) onActivate.accept(item);
        }
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
        float left = checkBoxes ? CHECK_COLUMN : 0f;
        for (int i = 0; i < index; i++) left += columns.get(i).width;
        return left;
    }

    /**
     * An x in the window as a distance into the table's content, which the sideways scroll moves.
     */
    private double contentX(double windowX) {
        return toLocalX(windowX) - getX() + getScrollOffsetX();
    }

    private float totalWidth() {
        float total = checkBoxes ? CHECK_COLUMN : 0f;
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
        if (editField != null) placeEditor();
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
        finishEdit(false, false);
        closeFont();
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        renderBackground(renderer);
        pushChildClip(renderer);
        renderRows(renderer);
        renderHeader(renderer);
        if (editField != null) {
            // the field scrolls with its row, so keep it from being drawn over the header
            renderer.pushClip((float) getX(), (float) getY() + headerHeight, (float) getWidth(), (float) getHeight() - headerHeight);
            renderChildren(renderer);
            renderer.popClip();
        } else {
            renderChildren(renderer);
        }
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
            if (checkBoxes) {
                drawCheckBox(renderer, x - scrollX + (CHECK_COLUMN - BOX_SIZE) / 2f, rowY + (rowHeight - BOX_SIZE) / 2f, checked.contains(item) ? 2 : 0, color);
            }
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
        if (column.ellipsize) text = TextFit.ellipsize(text, f, right - left, column.ellipsisInMiddle);
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

        if (checkBoxes) {
            int state = checked.isEmpty() ? 0 : checked.size() >= items.size() ? 2 : 1;
            drawCheckBox(renderer, x - scrollX + (CHECK_COLUMN - BOX_SIZE) / 2f, y + (headerHeight - BOX_SIZE) / 2f, state, titles);
        }
        for (int c = 0; c < columns.size(); c++) {
            Column<T> column = columns.get(c);
            float cellX = x + leftOf(c) - scrollX, cellW = widthOf(c);
            if (cellX + cellW <= x || cellX >= x + w) continue;
            float left = cellX + CELL_PADDING, right = cellX + cellW - CELL_PADDING;
            boolean sorted = column == sortColumn;
            if (sorted) right -= SORT_ARROW + 4f;
            if (right > left) {
                String title = column.ellipsize ? TextFit.ellipsize(column.title, f, right - left) : column.title;
                float textW = f.measureTextWidth(title);
                boolean clip = textW > right - left;
                float textX = switch (column.alignment) {
                    case RIGHT -> Math.max(left, right - textW);
                    case CENTER -> Math.max(left, (left + right - textW) / 2f);
                    default -> left;
                };
                if (clip) renderer.pushClip(left, y, right - left, headerHeight);
                renderer.drawText(title, textX, y + (headerHeight - textH) / 2f, f, titles);
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

    /** A box: {@code state} 0 is empty, 1 is a dash (some rows ticked) and 2 is a tick. */
    private static void drawCheckBox(Renderer renderer, float x, float y, int state, Paint color) {
        renderer.drawBorder(x, y, BOX_SIZE, BOX_SIZE, color, 1.5f, 3f);
        if (state == 2) {
            renderer.drawLine(x + 3f, y + BOX_SIZE / 2f, x + BOX_SIZE * 0.42f, y + BOX_SIZE - 4f, color, 2f);
            renderer.drawLine(x + BOX_SIZE * 0.42f, y + BOX_SIZE - 4f, x + BOX_SIZE - 3f, y + 4f, color, 2f);
        } else if (state == 1) {
            renderer.drawLine(x + 3.5f, y + BOX_SIZE / 2f, x + BOX_SIZE - 3.5f, y + BOX_SIZE / 2f, color, 2f);
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
