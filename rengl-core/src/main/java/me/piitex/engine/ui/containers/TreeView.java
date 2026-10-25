package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.image.IconCache;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.text.TextFit;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A scrolling list of items in a tree, each row indented under its parent with an arrow to expand or collapse it,
 * like the hierarchy panel of an editor or a file browser.
 * <p>
 * The tree does not hold its items. It asks the {@code children} function given to the constructor what is under
 * each one, starting from the {@link #setRoots roots}, and draws the rows itself, so it stays cheap with thousands of
 * items. After changing the model, call {@link #refresh()}. Expanded items and the selection are kept by the items'
 * {@code equals}.
 * <p>
 * The user can select a row by clicking it or with the arrow keys, select several with {@link #setMultiSelect},
 * tick check boxes with {@link #setCheckBoxes}, open one with a double-click or Enter
 * ({@link #onActivate}), rename one in place ({@link #startRename}), and, with {@link #setDragEnabled}, drag rows onto
 * other rows. The tree never changes the model: it reports what the user asked for through its callbacks, and the
 * callbacks make the change.
 * <p>
 * It follows the theme like a {@link Container} that {@link #useTheme() uses the theme}, with the theme's text and
 * selection colors for the rows. It selects the row under a
 * right-click before a {@link #setContextMenu context menu} opens, and before any {@link #onMouseClick} handler you set.
 */
public class TreeView<T> extends ScrollContainer implements Focusable, FontScalable {
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final double DRAG_THRESHOLD = 5;
    private static final double AUTO_SCROLL_EDGE = 24;
    private static final double AUTO_SCROLL_SPEED = 600; // points per second, with the pointer at the very edge
    private static final float PADDING = 4f;
    private static final float ICON_GAP = 6f; // between an item's icon and its name
    private static final float BOX_SIZE = 14f; // a check box

    /**
     * Where a dragged item was dropped: under {@code parent} (null for the top level), at {@code index} among its
     * children. The index is the gap the user dropped into, counting the children as they are now, the item itself
     * included if it is already there. That is how {@code Entity.addChild} and {@code EntityScene.add} count too, so the index can be passed straight to them.
     */
    public record Drop<T>(T item, T parent, int index) {
    }

    /**
     * A key press or repeat on the focused tree, offered before the tree's own keys. Return true to use it up.
     */
    @FunctionalInterface
    public interface KeyListener {
        boolean onKey(int key, int mods);
    }

    private record Row<T>(T item, Row<T> parent, int depth, boolean hasChildren) {
    }

    private enum DropMode {BEFORE, AFTER, INTO}

    private final Function<T, ? extends Collection<? extends T>> children;
    private Collection<? extends T> roots = List.of();
    private final Set<T> expanded = new HashSet<>();
    private final List<Row<T>> rows = new ArrayList<>();
    private T selected;                                 // the lead: the row the keyboard moves from; null if nothing is selected
    private final Set<T> selection = new LinkedHashSet<>(); // every selected item, the lead included; just the lead unless multiSelect
    private T anchor;                                   // where a Shift-click or Shift-arrow range starts
    private boolean multiSelect;
    private T pendingCollapse;                          // a press on a selected row in a multi-selection: it becomes the only one if released without dragging
    private final Set<T> checked = new LinkedHashSet<>();
    private boolean checkBoxes, cascadeChecks;
    private boolean ellipsize = true;
    private BiConsumer<T, Boolean> onCheck;
    private Consumer<List<T>> onSelectionChange;

    private Function<T, String> labelProvider = String::valueOf;
    private Function<T, T> parentProvider;
    private Predicate<T> dimmed = item -> false;
    private Function<T, IconAsset> iconProvider;
    private Function<T, Color> iconColorProvider;
    private Consumer<T> onSelect;
    private Consumer<T> onActivate;
    private BiConsumer<T, String> onRename;
    private Consumer<Drop<T>> onDrop;
    private BiPredicate<T, T> canDrop = (item, parent) -> true;
    private KeyListener keyListener;
    private boolean dragEnabled;
    private boolean toggleOnDoubleClick = true;

    private float rowHeight = 24f;
    private float indent = 16f;
    private float fontSize = 15f;
    private float iconSize = 14f;
    private float rowCornerRadius;
    private final Overridable<Paint> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Paint> dimTextColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> selectionColor = new Overridable<>(new Color(0.60f, 0.78f, 1.0f, 0.55f));

    private org.jetbrains.skia.Font font;
    private Typeface fontTypeface;
    // Derived colors, remade only when the colors they come from change.
    private boolean focused;

    // A press on a row, which becomes a drag once the pointer moves far enough.
    private Row<T> pressed;
    private boolean draggingRow, draggingThumb;
    private double pressX, pressY, lastX, lastY;
    private T lastPressItem;
    private long lastPressNanos;
    private Drop<T> drop;
    private DropMode dropMode;
    private int dropRow = -1;

    // Renaming in place.
    private TextFieldOverlay renameField;
    private T renaming;
    private String renameOriginal;

    /**
     * A tree at (0, 0). {@code children} lists what is under an item: an empty collection or null for a leaf.
     */
    public TreeView(double width, double height, Function<T, ? extends Collection<? extends T>> children) {
        super(width, height);
        this.children = Objects.requireNonNull(children, "children");
        setFocusTraversable(true);
        setBuiltInClickListener(event -> {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
            int index = rowAt(event.getMouseY());
            T item = index >= 0 ? rows.get(index).item : null;
            if (multiSelect && item != null && selection.contains(item)) {
                if (!Objects.equals(selected, item)) setLead(item); // the menu acts on the whole selection; keep it
                return;
            }
            selectByUser(item);
        });
        ThemeManager.register(this);
    }

    /* Model */

    /**
     * The top-level items. The collection is kept, not copied, and read again by every {@link #refresh()}, so a
     * live list (a scene's entities) stays current with a refresh after each change.
     */
    public void setRoots(Collection<? extends T> roots) {
        this.roots = roots == null ? List.of() : roots;
        refresh();
    }

    public Collection<? extends T> getRoots() {
        return roots;
    }

    /**
     * Reads the model again: call it after items are added, removed, moved or renamed. Expanded items stay expanded.
     * A selected item that is no longer shown (removed, or inside a collapsed item) is unselected, without
     * {@link #onSelect}.
     */
    public void refresh() {
        rows.clear();
        for (T root : roots) addRow(root, null, 0);
        pruneSelection();
        if (renaming != null) placeRenameField();
        if (pressed != null) {
            pressed = findRow(pressed.item);
            if (pressed == null) endRowDrag();
            else if (draggingRow) updateDrop();
        }
        scrollTo(getScrollOffsetX(), getScrollOffsetY()); // clamp to the new length
    }

    private void addRow(T item, Row<T> parent, int depth) {
        Collection<? extends T> kids = children.apply(item);
        boolean hasChildren = kids != null && !kids.isEmpty();
        Row<T> row = new Row<>(item, parent, depth, hasChildren);
        rows.add(row);
        if (hasChildren && expanded.contains(item)) {
            for (T kid : kids) addRow(kid, row, depth + 1);
        }
    }

    /**
     * How an item is written in its row. Defaults to {@code String.valueOf}.
     */
    public void setLabelProvider(Function<T, String> labelProvider) {
        this.labelProvider = labelProvider == null ? String::valueOf : labelProvider;
    }

    /**
     * How to find an item's parent (null at the top level), which {@link #reveal} uses to open the items above one.
     * Without it, reveal searches the tree from the roots, which is fine for a scene but slow for something like a
     * whole disk.
     */
    public void setParentProvider(Function<T, T> parentProvider) {
        this.parentProvider = parentProvider;
    }

    /**
     * Items drawn in the dim text color, such as inactive entities. None by default.
     */
    public void setDimmed(Predicate<T> dimmed) {
        this.dimmed = dimmed == null ? item -> false : dimmed;
    }

    /**
     * An icon drawn before each item's name, such as a folder or a file, or null for none on that item. Without a
     * provider, the default, the names start right after the arrows; with one, they all move over to make room.
     */
    public void setIconProvider(Function<T, IconAsset> iconProvider) {
        this.iconProvider = iconProvider;
        if (renaming != null) placeRenameField();
    }

    /**
     * The color each item's icon is drawn in. Null, or a null color for an item, draws it in the text color. A
     * {@link #setDimmed dimmed} item's icon is drawn faint.
     */
    public void setIconColorProvider(Function<T, Color> iconColorProvider) {
        this.iconColorProvider = iconColorProvider;
    }

    /**
     * The items in the rows shown now, top to bottom.
     */
    public List<T> getVisibleItems() {
        List<T> items = new ArrayList<>(rows.size());
        for (Row<T> row : rows) items.add(row.item);
        return items;
    }

    /* Expanding */

    public boolean isExpanded(T item) {
        return expanded.contains(item);
    }

    /**
     * Opens or closes an item. Closing the item above the selected one selects it instead, without
     * {@link #onSelect}.
     */
    public void setExpanded(T item, boolean expand) {
        if (expand ? !expanded.add(item) : !expanded.remove(item)) return;
        refreshKeepingSelection(item);
    }

    public void expand(T item) {
        setExpanded(item, true);
    }

    public void collapse(T item) {
        setExpanded(item, false);
    }

    /**
     * Opens every item in the tree.
     */
    public void expandAll() {
        List<T> stack = new ArrayList<>(roots);
        while (!stack.isEmpty()) {
            T item = stack.removeLast();
            Collection<? extends T> kids = children.apply(item);
            if (kids != null && !kids.isEmpty()) {
                expanded.add(item);
                stack.addAll(kids);
            }
        }
        refresh();
    }

    /**
     * Closes every item, leaving only the roots.
     */
    public void collapseAll() {
        if (expanded.isEmpty()) return;
        Row<T> top = findRow(selected);
        while (top != null && top.parent != null) top = top.parent;
        expanded.clear();
        refresh();
        if (top != null) selectOnly(top.item);
    }

    /**
     * Refreshes after an item was opened or closed. If the selection was inside a closed item, the closed item is
     * selected instead.
     */
    private void refreshKeepingSelection(T toggled) {
        boolean leadInside = selected != null && isBelow(findRow(selected), toggled);
        boolean anyInside = leadInside || selectionInside(toggled);
        refresh();
        if (!anyInside) return;
        if (leadInside) selectOnly(toggled);
        else selection.add(toggled); // part of a multi-selection was inside; the closed item stands for it
    }

    /**
     * Whether any selected item is under {@code ancestor}.
     */
    private boolean selectionInside(T ancestor) {
        for (T item : selection) {
            if (isBelow(findRow(item), ancestor)) return true;
        }
        return false;
    }

    private boolean isBelow(Row<T> row, T ancestor) {
        for (Row<T> r = row == null ? null : row.parent; r != null; r = r.parent) {
            if (Objects.equals(r.item, ancestor)) return true;
        }
        return false;
    }

    /**
     * Opens every item above {@code item} and scrolls its row into view. Returns false if the item is not in the
     * tree.
     */
    public boolean reveal(T item) {
        if (item == null) return false;
        if (indexOf(item) < 0) {
            List<T> path = pathTo(item);
            if (path == null) return false;
            expanded.addAll(path);
            refresh();
        }
        scrollToRow(indexOf(item));
        return true;
    }

    /**
     * The items above {@code item}, from the top down, or null if it is not in the tree.
     */
    private List<T> pathTo(T item) {
        List<T> path = new ArrayList<>();
        if (parentProvider != null) {
            for (T p = parentProvider.apply(item); p != null; p = parentProvider.apply(p)) path.addFirst(p);
            return !path.isEmpty() || roots.contains(item) ? path : null;
        }
        return search(roots, item, path) ? path : null;
    }

    private boolean search(Collection<? extends T> items, T target, List<T> path) {
        if (items == null) return false;
        for (T item : items) {
            if (Objects.equals(item, target)) return true;
            path.add(item);
            if (search(children.apply(item), target, path)) return true;
            path.removeLast();
        }
        return false;
    }

    /* Selection */

    /**
     * The selected item, or null. With {@link #setMultiSelect multi-select} it is the lead of the selection: the
     * row that was clicked or moved to last, which the arrow keys move on from. {@link #getSelectedItems()} has them all.
     */
    public T getSelected() {
        return selected;
    }

    /**
     * Selects only {@code item}, opening the items above it and scrolling to it, or clears the selection with null.
     * Does not fire {@link #onSelect}. An item that is not in the tree clears the selection.
     */
    public void setSelected(T item) {
        if (reveal(item)) selectOnly(item);
        else clearSelection();
    }

    /**
     * Whether the user can select several rows. Off by default. On, a click selects one row, Cmd-click (Ctrl-click
     * off macOS) adds or removes a row, Shift-click selects the rows from the last clicked one to this one, Shift with
     * the arrow keys, Home and End does the same from the keyboard, and Cmd/Ctrl+A selects every row shown. Turning it
     * off keeps only the lead item selected.
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
     * Every selected item, top to bottom as the rows are shown. Empty if nothing is selected.
     */
    public List<T> getSelectedItems() {
        List<T> items = new ArrayList<>(selection.size());
        for (Row<T> row : rows) {
            if (selection.contains(row.item)) items.add(row.item);
        }
        return items;
    }

    public boolean isSelected(T item) {
        return item != null && selection.contains(item);
    }

    /**
     * Selects exactly these items, opening the items above them. Items that are not in the tree are skipped. Without
     * {@link #setMultiSelect multi-select} only the last one is kept. Does not fire {@link #onSelect} or
     * {@link #onSelectionChange}. The last item becomes the lead, the first the start of a Shift range.
     */
    public void setSelectedItems(Collection<? extends T> items) {
        List<T> found = new ArrayList<>();
        for (T item : items) {
            if (reveal(item)) found.add(item);
        }
        selection.clear();
        selected = anchor = null;
        if (found.isEmpty()) return;
        if (!multiSelect) found = List.of(found.getLast());
        selection.addAll(found);
        selected = found.getLast();
        anchor = found.getFirst();
    }

    /**
     * Selects every row shown, when {@link #setMultiSelect multi-select} is on; otherwise does nothing. Silent.
     */
    public void selectAll() {
        if (!multiSelect || rows.isEmpty()) return;
        selection.clear();
        for (Row<T> row : rows) selection.add(row.item);
        selected = rows.getLast().item;
        anchor = rows.getFirst().item;
    }

    /**
     * Clears the selection. Silent.
     */
    public void clearSelection() {
        selection.clear();
        selected = anchor = null;
    }

    /**
     * Fired with the new lead item (null when cleared) when the user changes which row has the keyboard: a click, the
     * arrow keys, or closing the item above the selected one. Not fired by {@link #setSelected} or {@link #refresh}.
     * With multi-select, {@link #onSelectionChange} says when any row joined or left the selection.
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
     * Fired when the user double-clicks an item or presses Enter on the selected one. A double-click on an item with
     * children also opens or closes it, unless {@link #setToggleOnDoubleClick} turned that off.
     */
    public void onActivate(Consumer<T> onActivate) {
        this.onActivate = onActivate;
    }

    /**
     * Whether double-clicking an item with children opens or closes it. On by default.
     */
    public void setToggleOnDoubleClick(boolean toggleOnDoubleClick) {
        this.toggleOnDoubleClick = toggleOnDoubleClick;
    }

    public boolean isToggleOnDoubleClick() {
        return toggleOnDoubleClick;
    }

    /**
     * The user selects only {@code item} (or nothing, with null).
     */
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

    /**
     * Silently makes {@code item} the whole selection.
     */
    private void selectOnly(T item) {
        selection.clear();
        selection.add(item);
        selected = anchor = item;
    }

    /**
     * Makes an item that is already selected the lead.
     */
    private void setLead(T item) {
        selected = item;
        if (onSelect != null) onSelect.accept(item);
    }

    /**
     * Tells the listeners what a user change did. {@code leadBefore} is the lead from before the change.
     */
    private void notifySelection(T leadBefore) {
        if (onSelect != null && !Objects.equals(selected, leadBefore)) onSelect.accept(selected);
        if (onSelectionChange != null) onSelectionChange.accept(getSelectedItems());
    }

    /**
     * After the rows were rebuilt: forgets selected items that are no longer shown, and picks a new lead if needed.
     */
    private void pruneSelection() {
        if (selection.isEmpty()) {
            selected = anchor = null;
            return;
        }
        Set<T> visible = new HashSet<>();
        for (Row<T> row : rows) visible.add(row.item);
        selection.retainAll(visible);
        if (selected != null && !selection.contains(selected)) selected = null;
        if (anchor != null && !visible.contains(anchor)) anchor = null;
        if (selected == null && !selection.isEmpty()) selected = selection.iterator().next();
        if (selection.isEmpty()) selected = anchor = null;
    }

    /**
     * Selects the rows between {@code from} and {@code to} (either order), keeping the rest when {@code keep}.
     */
    private void selectRange(int from, int to, boolean keep) {
        if (!keep) selection.clear();
        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) selection.add(rows.get(i).item);
    }

    /**
     * The modifier keys held now, as GLFW bits. A press carries none, so the tree asks the window's input.
     */
    protected int heldModifiers() {
        Window window = getWindow();
        return window == null ? 0 : window.getInput().getModifiers();
    }

    /* Check boxes */

    /**
     * Whether each row has a check box before its icon and name, for choosing items apart from selecting them (the files
     * to extract, say). Off by default. A click on the box, or Space on the lead row, ticks it; the checked items are
     * {@link #getCheckedItems()}. Checking is independent of the selection.
     */
    public void setCheckBoxes(boolean checkBoxes) {
        this.checkBoxes = checkBoxes;
        if (renaming != null) placeRenameField();
    }

    public boolean isCheckBoxes() {
        return checkBoxes;
    }

    /**
     * Whether ticking an item with children ticks (or clears) everything under it too, as a folder does. Off by
     * default. There is no half-ticked state: a folder shows ticked only if it was ticked itself, so a folder whose
     * children were ticked one by one shows empty.
     */
    public void setCascadeChecks(boolean cascadeChecks) {
        this.cascadeChecks = cascadeChecks;
    }

    public boolean isCascadeChecks() {
        return cascadeChecks;
    }

    public boolean isChecked(T item) {
        return item != null && checked.contains(item);
    }

    /**
     * Every checked item, including ones inside closed items, in the order they were checked. Items are remembered by
     * {@code equals} and are not forgotten when removed from the model: call {@link #clearChecked()} when the tree is
     * loaded with something new.
     */
    public List<T> getCheckedItems() {
        return new ArrayList<>(checked);
    }

    /**
     * Ticks or clears an item (and what is under it, with {@link #setCascadeChecks}). Does not fire {@link #onCheck}.
     */
    public void setChecked(T item, boolean on) {
        if (item == null) return;
        applyCheck(item, on);
        if (cascadeChecks) {
            List<T> stack = new ArrayList<>(List.of(item));
            while (!stack.isEmpty()) {
                Collection<? extends T> kids = children.apply(stack.removeLast());
                if (kids == null) continue;
                for (T kid : kids) {
                    applyCheck(kid, on);
                    stack.add(kid);
                }
            }
        }
    }

    private void applyCheck(T item, boolean on) {
        if (on) checked.add(item);
        else checked.remove(item);
    }

    /**
     * Clears every check. Does not fire {@link #onCheck}.
     */
    public void clearChecked() {
        checked.clear();
    }

    /**
     * Fired with the item and whether it is now checked when the user ticks or clears a box. An item ticked only
     * because a parent was (with {@link #setCascadeChecks}) is not reported on its own.
     */
    public void onCheck(BiConsumer<T, Boolean> onCheck) {
        this.onCheck = onCheck;
    }

    private void toggleCheck(T item) {
        boolean on = !checked.contains(item);
        setChecked(item, on);
        if (onCheck != null) onCheck.accept(item, on);
    }

    /* Renaming */

    /**
     * Fired with the item and its new name when the user finishes renaming it with Enter or by clicking elsewhere,
     * if the name changed. Escape cancels. Update the model and call {@link #refresh()}.
     */
    public void onRename(BiConsumer<T, String> onRename) {
        this.onRename = onRename;
    }

    /**
     * Puts a text field over the item's name, with the name selected, for the user to type a new one. Does nothing
     * if the item is not in the tree.
     */
    public void startRename(T item) {
        finishRename(false, false);
        if (!reveal(item)) return;
        renaming = item;
        renameOriginal = labelProvider.apply(item);
        renameField = new TextFieldOverlay(0, 0) {
            @Override
            public void onKeyPressed(int key, int action, int mods) {
                if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
                    finishRename(false, true);
                    return;
                }
                super.onKeyPressed(key, action, mods);
            }
        };
        renameField.setFontSize(fontSize);
        renameField.setPadding(PADDING);
        renameField.setText(renameOriginal);
        renameField.selectAll();
        renameField.onSubmit(text -> finishRename(true, true));
        renameField.onFocusChanged(gained -> {
            if (!gained) finishRename(true, false);
        });
        placeRenameField();
        addElement(renameField);
        PopupHost host = getPopupHost(); // the window, or the screen the tree is shown on
        if (host != null) host.requestFocus(renameField);
    }

    /**
     * Whether a rename is in progress.
     */
    public boolean isRenaming() {
        return renaming != null;
    }

    /**
     * Ends a rename in progress: {@code commit} reports the new name. Enter and Escape give the keyboard back to
     * the tree; a click elsewhere has already moved it, so {@code refocus} is false then.
     */
    private void finishRename(boolean commit, boolean refocus) {
        TextFieldOverlay field = renameField;
        if (field == null) return;
        T item = renaming;
        renameField = null;
        renaming = null;
        removeElement(field);
        PopupHost host = getPopupHost();
        if (refocus && host != null) host.requestFocus(this);
        String name = field.getText();
        field.dispose();
        if (commit && onRename != null && !name.equals(renameOriginal)) onRename.accept(item, name);
    }

    private void placeRenameField() {
        int index = indexOf(renaming);
        if (index < 0) {
            finishRename(false, false);
            return;
        }
        float x = labelX(rows.get(index)) - PADDING;
        renameField.setBounds(x, index * rowHeight, Math.max(40, getWidth() - x - PADDING), rowHeight);
    }

    /* Dragging rows */

    /**
     * Whether rows can be dragged onto other rows. Off by default. A drop is reported through {@link #onDrop}.
     */
    public void setDragEnabled(boolean dragEnabled) {
        this.dragEnabled = dragEnabled;
        if (!dragEnabled) endRowDrag();
    }

    public boolean isDragEnabled() {
        return dragEnabled;
    }

    /**
     * Fired when the user drops a dragged item somewhere new. Make the move in the model and call {@link #refresh()}.
     */
    public void onDrop(Consumer<Drop<T>> onDrop) {
        this.onDrop = onDrop;
    }

    /**
     * Asked with (item, new parent) whether the item may go there; the parent is null for the top level. The tree
     * already refuses dropping an item into itself or its own children.
     */
    public void setCanDrop(BiPredicate<T, T> canDrop) {
        this.canDrop = canDrop == null ? (item, parent) -> true : canDrop;
    }

    /**
     * Whether the user is dragging a row.
     */
    public boolean isDraggingRow() {
        return draggingRow;
    }

    private void endRowDrag() {
        pressed = null;
        draggingRow = false;
        clearDrop();
    }

    private void clearDrop() {
        drop = null;
        dropMode = null;
        dropRow = -1;
    }

    /**
     * Works out where the dragged row would land with the pointer at (lastX, lastY). The top quarter of a row puts
     * it before that row, the bottom quarter after it (or first inside it, when it is open), and the middle inside
     * it, at the end. Below the last row is the end of the top level, and above the first row the start of it.
     */
    private void updateDrop() {
        clearDrop();
        double contentY = toLocalY(lastY) - getY() + getScrollOffsetY();
        if (rows.isEmpty()) return;
        if (contentY < 0) {
            offer(null, 0, DropMode.BEFORE, 0); // above the tree, as it scrolls up: before the first row
            return;
        }
        int index = (int) Math.floor(contentY / rowHeight);
        if (index >= rows.size()) {
            offer(null, roots.size(), DropMode.AFTER, rows.size() - 1);
            return;
        }
        Row<T> row = rows.get(index);
        double within = contentY / rowHeight - index;
        if (within < 0.25) {
            offer(row.parent, siblingIndex(row), DropMode.BEFORE, index);
        } else if (within > 0.75 && !(row.hasChildren && expanded.contains(row.item))) {
            offer(row.parent, siblingIndex(row) + 1, DropMode.AFTER, index);
        } else if (within > 0.75) {
            offer(row, 0, DropMode.AFTER, index); // just under an open item's name is its first child
        } else {
            offer(row, size(children.apply(row.item)), DropMode.INTO, index);
        }
    }

    /**
     * Sets the drop to position {@code index} under {@code parentRow} (null: the top level) if that is allowed and
     * actually moves something.
     */
    private void offer(Row<T> parentRow, int index, DropMode mode, int indicatorRow) {
        T item = pressed.item;
        for (Row<T> r = parentRow; r != null; r = r.parent) {
            if (r == pressed) return; // into itself or its own children
        }
        T parent = parentRow == null ? null : parentRow.item;
        if (!canDrop.test(item, parent)) return;
        T oldParent = pressed.parent == null ? null : pressed.parent.item;
        if (Objects.equals(parent, oldParent)) {
            int oldIndex = siblingIndex(pressed);
            if (index == oldIndex || index == oldIndex + 1) return; // the gaps either side of it: no move
        }
        drop = new Drop<>(item, parent, index);
        dropMode = mode;
        dropRow = indicatorRow;
    }

    private int siblingIndex(Row<T> row) {
        Collection<? extends T> siblings = row.parent == null ? roots : children.apply(row.parent.item);
        int i = 0;
        for (T sibling : siblings) {
            if (Objects.equals(sibling, row.item)) return i;
            i++;
        }
        return -1;
    }

    private static int size(Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }

    /* Keys */

    /**
     * Offered every key press and repeat while the tree has the keyboard, before the tree's own keys (the arrows,
     * Home, End, Enter). Use it for keys like Delete or F2.
     */
    public void onKey(KeyListener keyListener) {
        this.keyListener = keyListener;
    }

    /**
     * Whether the tree has the keyboard.
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
        if (rows.isEmpty()) return;
        boolean shift = multiSelect && (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (multiSelect && key == GLFW.GLFW_KEY_A && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0) {
            T before = selected;
            selectAll();
            notifySelection(before);
            return;
        }
        int index = indexOf(selected);
        Row<T> row = index >= 0 ? rows.get(index) : null;
        switch (key) {
            case GLFW.GLFW_KEY_UP -> index = index < 0 ? rows.size() - 1 : Math.max(0, index - 1);
            case GLFW.GLFW_KEY_DOWN -> index = Math.min(rows.size() - 1, index + 1);
            case GLFW.GLFW_KEY_HOME -> index = 0;
            case GLFW.GLFW_KEY_END -> index = rows.size() - 1;
            case GLFW.GLFW_KEY_PAGE_UP -> index = Math.max(0, index - pageRows());
            case GLFW.GLFW_KEY_PAGE_DOWN -> index = Math.min(rows.size() - 1, Math.max(0, index) + pageRows());
            case GLFW.GLFW_KEY_LEFT -> {
                if (row == null) return;
                if (row.hasChildren && expanded.contains(row.item)) {
                    collapse(row.item);
                    return;
                }
                if (row.parent == null) return;
                index = indexOf(row.parent.item);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (row == null || !row.hasChildren) return;
                if (!expanded.contains(row.item)) {
                    expand(row.item);
                    return;
                }
                index++; // its first child
            }
            case GLFW.GLFW_KEY_SPACE -> {
                if (row != null && checkBoxes && action == GLFW.GLFW_PRESS) toggleCheck(row.item);
                return;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (row != null && onActivate != null && action == GLFW.GLFW_PRESS) onActivate.accept(row.item);
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
            selected = rows.get(index).item;
            notifySelection(before);
        } else {
            selectByUser(rows.get(index).item);
        }
        scrollToRow(index);
    }

    private int pageRows() {
        return Math.max(1, (int) (getHeight() / rowHeight) - 1);
    }

    /* Mouse */

    /**
     * A press anywhere on the tree is the tree's: it selects, opens or starts dragging a row, or drags the scrollbar.
     */
    @Override
    public boolean canDrag(double x, double y) {
        return true;
    }

    @Override
    public void onDragStart(double x, double y) {
        if (thumbAt(x, y)) {
            draggingThumb = true;
            super.onDragStart(x, y);
            return;
        }
        int index = rowAt(y);
        if (index < 0) {
            selectByUser(null);
            return;
        }
        Row<T> row = rows.get(index);
        double localX = toLocalX(x);
        if (row.hasChildren && localX >= arrowX(row) && localX < arrowX(row) + indent) {
            T item = row.item;
            boolean inside = (selected != null && isBelow(findRow(selected), item)) || selectionInside(item);
            T before = selected;
            setExpanded(item, !expanded.contains(item)); // moves the selection to the item quietly; report it as the user's change
            if (inside) notifySelection(before);
            return;
        }
        if (checkBoxes && localX >= getX() + boxX(row) - ICON_GAP / 2f && localX < getX() + boxX(row) + BOX_SIZE + ICON_GAP / 2f) {
            toggleCheck(row.item);
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
                selected = row.item;
            } else if (range) {
                selectOnly(row.item);
            } else if (selection.add(row.item)) {
                selected = anchor = row.item;
            } else {
                selection.remove(row.item);
                anchor = row.item;
                if (Objects.equals(selected, row.item)) selected = selection.isEmpty() ? null : selection.stream().reduce((a, b) -> b).orElse(null);
            }
            notifySelection(before);
            return;
        }
        long now = System.nanoTime();
        boolean doubleClick = Objects.equals(row.item, lastPressItem) && now - lastPressNanos <= DOUBLE_CLICK_NANOS;
        lastPressItem = row.item;
        lastPressNanos = doubleClick ? 0 : now; // a third press starts a new pair
        if (multiSelect && selection.size() > 1 && selection.contains(row.item) && !doubleClick) {
            // Pressing one of several selected rows may start dragging all of them; it only picks this one on release.
            pendingCollapse = row.item;
            if (!Objects.equals(selected, row.item)) setLead(row.item);
        } else {
            selectByUser(row.item);
        }
        if (doubleClick) {
            if (toggleOnDoubleClick && row.hasChildren) setExpanded(row.item, !expanded.contains(row.item));
            if (onActivate != null) onActivate.accept(row.item);
            return;
        }
        pressed = row;
        pressX = lastX = x;
        pressY = lastY = y;
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (draggingThumb) {
            super.onDragUpdate(x, y);
            return;
        }
        if (pressed == null || !dragEnabled) return;
        lastX = x;
        lastY = y;
        if (!draggingRow) {
            if (Math.hypot(x - pressX, y - pressY) < DRAG_THRESHOLD) return;
            draggingRow = true;
            lastPressItem = null; // a drag is not the first half of a double-click
        }
        updateDrop();
    }

    @Override
    public void onDragEnd(double x, double y) {
        if (draggingThumb) {
            draggingThumb = false;
            super.onDragEnd(x, y);
            return;
        }
        boolean wasDragging = draggingRow;
        Drop<T> dropped = wasDragging ? drop : null;
        endRowDrag();
        T collapseTo = pendingCollapse;
        pendingCollapse = null;
        if (collapseTo != null && !wasDragging) selectByUser(collapseTo);
        if (dropped != null && onDrop != null) onDrop.accept(dropped);
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(dimTextColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(selectionColor.get(), delta);
        if (draggingRow) {
            double top = getAbsoluteY(), bottom = top + getHeight(), push = 0;
            if (lastY < top + AUTO_SCROLL_EDGE) {
                push = -Math.min(1, (top + AUTO_SCROLL_EDGE - lastY) / AUTO_SCROLL_EDGE);
            } else if (lastY > bottom - AUTO_SCROLL_EDGE) {
                push = Math.min(1, (lastY - (bottom - AUTO_SCROLL_EDGE)) / AUTO_SCROLL_EDGE);
            }
            if (push != 0) {
                scrollBy(0, push * AUTO_SCROLL_SPEED * delta);
                updateDrop();
            }
        }
    }

    /* Geometry */

    /**
     * The row under a window y, or -1.
     */
    private int rowAt(double windowY) {
        double contentY = toLocalY(windowY) - getY() + getScrollOffsetY();
        if (contentY < 0) return -1;
        int index = (int) (contentY / rowHeight);
        return index < rows.size() ? index : -1;
    }

    private int indexOf(T item) {
        if (item == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            if (Objects.equals(rows.get(i).item, item)) return i;
        }
        return -1;
    }

    private Row<T> findRow(T item) {
        int index = indexOf(item);
        return index < 0 ? null : rows.get(index);
    }

    /**
     * The left edge of a row's arrow, relative to the tree.
     */
    private float arrowX(Row<T> row) {
        return (float) getX() + PADDING + row.depth * indent;
    }

    /**
     * The left edge of a row's name, relative to the tree's content (as a child's x is).
     */
    private float labelX(Row<T> row) {
        return iconX(row) + (iconProvider == null ? 0f : iconSize + ICON_GAP);
    }

    /**
     * The left edge of a row's icon, relative to the tree's content.
     */
    private float iconX(Row<T> row) {
        return boxX(row) + (checkBoxes ? BOX_SIZE + ICON_GAP : 0f);
    }

    /**
     * The left edge of a row's check box, relative to the tree's content.
     */
    private float boxX(Row<T> row) {
        return PADDING + (row.depth + 1) * indent + 2f;
    }

    private void scrollToRow(int index) {
        if (index < 0) return;
        double top = index * rowHeight, bottom = top + rowHeight, offset = getScrollOffsetY();
        if (top < offset) {
            scrollTo(getScrollOffsetX(), top);
        } else if (bottom > offset + getHeight()) {
            scrollTo(getScrollOffsetX(), bottom - getHeight());
        }
    }

    /**
     * The rows decide how far the tree scrolls; the rename field does not count.
     */
    @Override
    public double getMaxScrollY() {
        return Math.max(0, rows.size() * rowHeight + PADDING - getHeight());
    }

    @Override
    public double getMaxScrollX() {
        return 0;
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
        if (renaming != null) placeRenameField();
    }

    public float getIndent() {
        return indent;
    }

    public float getIconSize() {
        return iconSize;
    }

    /**
     * The width and height of the {@link #setIconProvider icons}. Default 14.
     */
    public void setIconSize(float iconSize) {
        this.iconSize = Math.max(1f, iconSize);
        if (renaming != null) placeRenameField();
    }

    public boolean isEllipsize() {
        return ellipsize;
    }

    /**
     * Whether a row's text that reaches the tree's right edge is ended with an ellipsis ({@code …}). On by default;
     * off lets the edge cut it.
     */
    public void setEllipsize(boolean ellipsize) {
        this.ellipsize = ellipsize;
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

    /**
     * How far each level is indented; the arrow takes the same width. Default 16.
     */
    public void setIndent(float indent) {
        this.indent = Math.max(0f, indent);
        if (renaming != null) placeRenameField();
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
     * Pins the text color against theme switches.
     */
    public void setTextColor(Paint color) {
        textColor.set(color);
    }

    /**
     * Pins the color of {@link #setDimmed dimmed} items.
     */
    public void setDimTextColor(Paint color) {
        dimTextColor.set(color);
    }

    /**
     * Pins the selected row's color. The drop marker uses it too, fully opaque.
     */
    public void setSelectionColor(Paint color) {
        selectionColor.set(color);
    }

    @Override
    public void applyTheme(Theme theme) {
        super.applyTheme(theme);
        textColor.applyTheme(theme.getText());
        dimTextColor.applyTheme(theme.getPlaceholderText());
        selectionColor.applyTheme(theme.getAccentTint());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        textColor.clearExplicit();
        dimTextColor.clearExplicit();
        selectionColor.clearExplicit();
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
     * Frees the font. The tree can still be drawn afterwards; it makes a new one.
     */
    public void dispose() {
        closeFont();
        if (renameField != null) renameField.dispose();
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        renderBackground(renderer);
        pushChildClip(renderer);
        renderRows(renderer);
        renderChildren(renderer);
        renderer.popClip();
        if (isScrollbarVisible()) renderScrollbars(renderer);
    }

    private void renderRows(Renderer renderer) {
        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        float scroll = (float) getScrollOffsetY();
        org.jetbrains.skia.Font f = getFont();
        float textH = FontMetricsCompat.of(f).getHeight();
        Paint text = textColor.get();
        Paint selectionPaint = selectionColor.get();
        // The selection is translucent so the row's text shows through; the drop marker wants it solid.
        Paint marker = AnimatedPaint.resolve(selectionPaint) instanceof Color c ? c.withAlpha(1f) : selectionPaint;
        Color iconTint = solid(text);

        int hoverRow = -1;
        double mx = MouseTracker.getX(), my = MouseTracker.getY();
        if (!draggingRow && !draggingThumb && isHovered() && MouseTracker.isInWindow() && contains(mx, my) && !thumbAt(mx, my)) {
            hoverRow = rowAt(my);
        }

        int first = Math.max(0, (int) (scroll / rowHeight));
        for (int i = first; i < rows.size(); i++) {
            float rowY = y + i * rowHeight - scroll;
            if (rowY >= y + h) break;
            Row<T> row = rows.get(i);
            if (selection.contains(row.item)) {
                if (!focused) renderer.pushAlpha(0.6f);
                renderer.drawQuad(x, rowY, w, rowHeight, selectionPaint, rowCornerRadius);
                if (!focused) renderer.popAlpha();
            } else if (i == hoverRow) {
                renderer.pushAlpha(0.07f);
                renderer.drawQuad(x, rowY, w, rowHeight, text, rowCornerRadius);
                renderer.popAlpha();
            }
            if (i == dropRow && dropMode == DropMode.INTO) {
                renderer.drawBorder(x + 1, rowY + 1, w - 2, rowHeight - 2, marker, 2f, 3f);
            }
            if (row.hasChildren) {
                drawArrow(renderer, arrowX(row) + indent / 2f, rowY + rowHeight / 2f, expanded.contains(row.item), text);
            }
            boolean dim = dimmed.test(row.item);
            if (checkBoxes) drawCheckBox(renderer, x + boxX(row), rowY + (rowHeight - BOX_SIZE) / 2f, checked.contains(row.item), dim ? dimTextColor.get() : text);
            IconAsset icon = iconProvider == null ? null : iconProvider.apply(row.item);
            if (icon != null) {
                int size = Math.round(iconSize);
                Color tint = iconColorProvider == null ? null : iconColorProvider.apply(row.item);
                renderer.drawImage(IconCache.loadResource(icon.resourcePath(), size, size), x + iconX(row),
                        rowY + (rowHeight - size) / 2f, size, size, 0f, dim ? 0.45f : 1f, tint == null ? iconTint : tint);
            }
            if (!Objects.equals(row.item, renaming)) {
                Paint color = dim ? dimTextColor.get() : text;
                String label = labelProvider.apply(row.item);
                if (ellipsize && label != null) label = TextFit.ellipsize(label, f, w - labelX(row) - PADDING);
                renderer.drawText(label, x + labelX(row), rowY + (rowHeight - textH) / 2f, f, color);
            }
        }

        if (dropMode == DropMode.BEFORE || dropMode == DropMode.AFTER) {
            float lineY = y + dropRow * rowHeight - scroll + (dropMode == DropMode.AFTER ? rowHeight : 0f);
            lineY = Math.clamp(lineY, y + 1f, y + h - 1f); // at the top or bottom edge, keep the whole line in view
            int depth = drop.parent() == null ? 0 : findRow(drop.parent()).depth + 1;
            float lineX = x + PADDING + depth * indent + indent;
            renderer.drawLine(lineX, lineY, x + w - PADDING, lineY, marker, 2f);
            renderer.drawCircle(lineX, lineY, 3f, marker);
        }
    }

    /**
     * The color to tint a monochrome icon with: the paint itself, or a gradient's first stop.
     */
    private static Color solid(Paint paint) {
        return switch (AnimatedPaint.resolve(paint)) {
            case Color c -> c;
            case LinearGradient g -> g.getColors().get(0);
            case null, default -> Color.BLACK;
        };
    }

    private static void drawCheckBox(Renderer renderer, float x, float y, boolean on, Paint color) {
        renderer.drawBorder(x, y, BOX_SIZE, BOX_SIZE, color, 1.5f, 3f);
        if (on) {
            renderer.drawLine(x + 3f, y + BOX_SIZE / 2f, x + BOX_SIZE * 0.42f, y + BOX_SIZE - 4f, color, 2f);
            renderer.drawLine(x + BOX_SIZE * 0.42f, y + BOX_SIZE - 4f, x + BOX_SIZE - 3f, y + 4f, color, 2f);
        }
    }

    private static void drawArrow(Renderer renderer, float cx, float cy, boolean open, Paint color) {
        float r = 3.5f;
        if (open) {
            renderer.drawLine(cx - r, cy - r / 2f, cx, cy + r / 2f, color, 1.5f);
            renderer.drawLine(cx, cy + r / 2f, cx + r, cy - r / 2f, color, 1.5f);
        } else {
            renderer.drawLine(cx - r / 2f, cy - r, cx + r / 2f, cy, color, 1.5f);
            renderer.drawLine(cx + r / 2f, cy, cx - r / 2f, cy + r, color, 1.5f);
        }
    }
}
