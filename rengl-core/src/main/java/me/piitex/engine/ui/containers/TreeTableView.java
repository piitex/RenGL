package me.piitex.engine.ui.containers;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A {@link TableView} whose rows form a tree: each row is indented under its parent in the
 * {@linkplain #setTreeColumn tree column}, with an arrow to open or close it, and the other columns line up across
 * every level. Think of a project's files with their size and date, or a scene's entities with a visibility switch
 * per row.
 * <pre>{@code
 * TreeTableView<Node> tree = new TreeTableView<>(600, 400, Node::children);
 * tree.addColumn("Name", Node::name).setWidth(260);
 * tree.addColumn("Size", n -> formatSize(n.size())).setAlignment(TextAlign.RIGHT);
 * tree.addColumn("Visible", n -> "").setCellWidget(() -> new ToggleSwitchOverlay(40, 22), (s, n) -> s.setOn(n.visible()));
 * tree.setRoots(scene.roots());
 * }</pre>
 * Everything a table does works here too: clicking a header sorts each level on its own (children stay under their
 * parent), columns resize, cells can be edited in place, rows can have check boxes, and columns can hold
 * {@linkplain TableView.Column#setCellWidget live controls}, made only for the rows on screen.
 * <p>
 * Like {@link TreeView} it does not hold the items. It asks the {@code children} function what is under each one,
 * starting from the {@link #setRoots roots}, so only the open part of the tree is ever read. After changing the
 * model, call {@link #refresh()}. Open items, the selection and the ticks are kept by the items' {@code equals}; a tick
 * inside a closed item is kept too.
 * <p>
 * The user opens or closes an item by clicking its arrow, double-clicking its row (see
 * {@link #setToggleOnDoubleClick}), or with the Left and Right keys, which also move to the parent and the first child.
 * {@link #getItems()} is the rows shown now, top to bottom.
 * <p>
 * With {@link #setDragEnabled}, rows can be dragged to reorder them or move them under another item; the move is
 * reported through {@link #onDrop} for the model to make.
 */
public class TreeTableView<T> extends TableView<T> {

    /**
     * A tree table at (0, 0) with no columns. {@code children} lists what is under an item: an empty collection or
     * null for a leaf.
     */
    public TreeTableView(double width, double height, Function<T, ? extends Collection<? extends T>> children) {
        super(width, height);
        setTreeChildren(Objects.requireNonNull(children, "children"));
    }

    /**
     * The top-level items. The collection is kept, not copied, and read again by every {@link #refresh()}. Same as
     * {@link #setItems}.
     */
    public void setRoots(Collection<? extends T> roots) {
        setItems(roots);
    }

    /**
     * The rows shown now, top to bottom: the roots and everything inside open items.
     */
    @Override
    public List<T> getItems() {
        return super.getItems();
    }

    @Override
    public boolean isExpanded(T item) {
        return super.isExpanded(item);
    }

    /**
     * Opens or closes an item. Closing the item above the selected row selects it instead, without
     * {@link #onSelect}.
     */
    @Override
    public void setExpanded(T item, boolean expand) {
        super.setExpanded(item, expand);
    }

    public void expand(T item) {
        setExpanded(item, true);
    }

    public void collapse(T item) {
        setExpanded(item, false);
    }

    /**
     * Opens every item in the tree. This reads the whole tree, so keep it for trees that are not huge.
     */
    @Override
    public void expandAll() {
        super.expandAll();
    }

    /**
     * Closes every item, leaving only the roots. A selected row inside one moves to its root.
     */
    @Override
    public void collapseAll() {
        super.collapseAll();
    }

    /**
     * Opens every item above {@code item} and scrolls its row into view. Returns false if the item is not in the
     * tree. It searches from the roots, so it reads the tree down to the item.
     */
    @Override
    public boolean reveal(T item) {
        return super.reveal(item);
    }

    /**
     * How deep {@code item}'s row is, 0 for a root, or -1 if it is not shown (it is inside a closed item, or not in
     * the tree).
     */
    @Override
    public int depthOf(T item) {
        return super.depthOf(item);
    }

    /**
     * The item {@code item} is shown under, or null for a root or a row that is not shown.
     */
    @Override
    public T parentOf(T item) {
        return super.parentOf(item);
    }

    /**
     * The column that shows the arrows and is indented by depth. Null, the default, is the first column.
     */
    @Override
    public void setTreeColumn(TableView.Column<T> column) {
        super.setTreeColumn(column);
    }

    /**
     * The column that shows the arrows: the one set, else the first, or null while there are no columns.
     */
    @Override
    public TableView.Column<T> getTreeColumn() {
        return super.getTreeColumn();
    }

    @Override
    public float getIndent() {
        return super.getIndent();
    }

    /**
     * How far each level is indented in the tree column; the arrow takes the same width. Default 16.
     */
    @Override
    public void setIndent(float indent) {
        super.setIndent(indent);
    }

    /**
     * Whether double-clicking a row with children opens or closes it, before {@link #onActivate}. On by default. A
     * double-click on an {@linkplain TableView.Column#setEditable editable} cell edits it instead.
     */
    @Override
    public void setToggleOnDoubleClick(boolean toggleOnDoubleClick) {
        super.setToggleOnDoubleClick(toggleOnDoubleClick);
    }

    @Override
    public boolean isToggleOnDoubleClick() {
        return super.isToggleOnDoubleClick();
    }

    /**
     * Whether rows can be dragged to move them: before or after another row, or onto one to go inside it. Off by
     * default. The table does not change the model; it reports the move through {@link #onDrop}.
     * <p>
     * While dragging, a line shows where the rows would go and at what depth, or a frame around the row they would go
     * into; holding them over a closed item opens it, and the table scrolls when the pointer is near its top or bottom
     * edge. Escape drops the drag. With {@linkplain #setMultiSelect multi-select}, pressing one of several selected rows
     * drags them all. When a column sorts the rows, each level keeps the sort's order, so a drag can move rows to another
     * parent but not reorder them among the same siblings.
     */
    public void setDragEnabled(boolean dragEnabled) {
        setRowDragEnabled(dragEnabled);
    }

    public boolean isDragEnabled() {
        return isRowDragEnabled();
    }

    /**
     * Fired when the user drops dragged rows somewhere new, with where they go: the same {@link TreeView.Drop} a
     * {@link TreeView} gives, its index counted among the new parent's children as the {@code children} function lists
     * them. Make the move in the model and call {@link #refresh()}.
     */
    @Override
    public void onDrop(Consumer<TreeView.Drop<T>> onDrop) {
        super.onDrop(onDrop);
    }

    /**
     * Asked with (item, new parent) whether the item may go there; the parent is null for the top level. The table
     * already refuses dropping an item into itself or its own children.
     */
    @Override
    public void setCanDrop(BiPredicate<T, T> canDrop) {
        super.setCanDrop(canDrop);
    }

    /**
     * Whether the user is dragging a row.
     */
    @Override
    public boolean isDraggingRow() {
        return super.isDraggingRow();
    }
}
