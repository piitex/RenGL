package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
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
import me.piitex.engine.ui.text.TextFit;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A scrolling list of items, one per row, where the user picks one (or several). It is the plain list a {@link TableView}
 * with one column and no header would be: a settings page's category list, a save slot picker, a mod list.
 * <pre>{@code
 * ListBox<Save> saves = new ListBox<>(240, 300);
 * saves.setLabelProvider(Save::name);
 * saves.setItems(allSaves);
 * saves.onSelect(save -> preview(save));
 * saves.onActivate(save -> load(save));
 * }</pre>
 * Only the rows on screen are drawn, so a list of thousands costs no more than a short one. The list keeps the items by
 * reference and shows them in the order given; {@link #setFilter} hides some without removing them. After changing the
 * collection given to {@link #setItems}, call {@link #refresh()}. The selection is kept by the items' {@code equals}.
 * <p>
 * The user selects a row by clicking it or with the arrow, Home, End and Page keys, selects several with {@link
 * #setMultiSelect} (Ctrl-click toggles, Shift-click selects a range, Ctrl+A selects all), opens one with a double-click or
 * Enter ({@link #onActivate}), and jumps to a row by typing the start of its text. The rows can show an {@linkplain
 * #setIconProvider icon}, or be drawn entirely by a {@link RowRenderer}. A {@link #setContextMenu context menu} opens on a
 * right-click after selecting the row under the pointer, unless it is already part of the selection.
 * <p>
 * It follows the theme like a {@link Container} that {@link #useTheme() uses the theme}.
 */
public class ListBox<T> extends ScrollContainer implements Focusable, FontScalable {
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final long TYPE_AHEAD_NANOS = 800_000_000L;
    private static final float TEXT_PADDING = 8f;

    /**
     * Draws a whole row instead of the default icon and text. It is given the row's box, already clipped to the list, in
     * the window's coordinates; the selection and hover highlight are drawn first, underneath.
     */
    @FunctionalInterface
    public interface RowRenderer<T> {
        void render(Renderer renderer, T item, float x, float y, float width, float height, boolean selected, boolean hovered);
    }

    /**
     * A key press or repeat on the focused list, offered before the list's own keys. Return true to use it up.
     */
    @FunctionalInterface
    public interface KeyListener {
        boolean onKey(int key, int mods);
    }

    private final List<T> source = new ArrayList<>();
    private final List<T> items = new ArrayList<>(); // as shown: the source through the filter
    private Predicate<T> filter;

    private T selected;
    private final Set<T> selection = new LinkedHashSet<>();
    private T anchor;
    private boolean multiSelect;

    private Function<T, String> labelProvider = String::valueOf;
    private Function<T, IconAsset> iconProvider;
    private RowRenderer<T> rowRenderer;
    private Predicate<T> dimmed = item -> false;
    private String emptyText = "";
    private Consumer<T> onSelect;
    private Consumer<List<T>> onSelectionChange;
    private Consumer<T> onActivate;
    private KeyListener keyListener;

    private float rowHeight = 26f;
    private float fontSize = 15f;
    private float iconSize = 16f;
    private float rowCornerRadius;
    private boolean striped;
    private final Overridable<Paint> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Paint> dimTextColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> selectionColor = new Overridable<>(new Color(0.60f, 0.78f, 1.0f, 0.55f));

    private org.jetbrains.skia.Font font;
    private Typeface fontTypeface;
    private boolean focused;
    private boolean draggingThumb;
    private T lastPressItem;
    private long lastPressNanos;
    private final StringBuilder typed = new StringBuilder();
    private long lastTypedNanos;

    public ListBox(double width, double height) {
        super(width, height);
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
            selectByUser(item);
        });
        ThemeManager.register(this);
    }

    /* Model */

    /**
     * The items, in the order shown. The collection is copied; to show a change to a live list, pass it again or call
     * {@link #refresh()} after {@link #setItems}. The list scrolls back to the top; the selection is kept for the items
     * that are still there, silently.
     */
    public void setItems(Collection<? extends T> newItems) {
        source.clear();
        if (newItems != null) source.addAll(newItems);
        refresh();
        scrollTo(0, 0);
    }

    /**
     * Adds {@code item} at the end.
     */
    public void addItem(T item) {
        source.add(item);
        refresh();
    }

    /**
     * Removes {@code item} (by {@code equals}), unselecting it. Returns whether it was there.
     */
    public boolean removeItem(T item) {
        boolean removed = source.remove(item);
        if (removed) refresh();
        return removed;
    }

    public void clear() {
        source.clear();
        refresh();
    }

    /**
     * Applies the filter to the items again and prunes the selection. Call it after the text a {@link #setLabelProvider
     * label provider} gives has changed in a way that matters to the filter. Keeps the scroll position, clamped.
     */
    public void refresh() {
        items.clear();
        for (T item : source) {
            if (filter == null || filter.test(item)) items.add(item);
        }
        pruneSelection();
        clampScroll();
    }

    /**
     * The items shown now, top to bottom, after the filter.
     */
    public List<T> getItems() {
        return List.copyOf(items);
    }

    /**
     * Every item, including the ones the filter hides.
     */
    public List<T> getAllItems() {
        return List.copyOf(source);
    }

    public int size() {
        return items.size();
    }

    /**
     * Shows only the items {@code filter} accepts; null shows all. A search box typically calls this with
     * {@code item -> label(item).toLowerCase().contains(query)}. Selected items that are hidden become unselected.
     */
    public void setFilter(Predicate<T> filter) {
        this.filter = filter;
        refresh();
    }

    public Predicate<T> getFilter() {
        return filter;
    }

    /**
     * How an item becomes the text of its row. {@code String.valueOf} by default.
     */
    public void setLabelProvider(Function<T, String> labelProvider) {
        this.labelProvider = labelProvider == null ? String::valueOf : labelProvider;
    }

    /**
     * The icon at the start of an item's row, or null for none. The icon is tinted with the text color.
     */
    public void setIconProvider(Function<T, IconAsset> iconProvider) {
        this.iconProvider = iconProvider;
    }

    /**
     * Draws the rows yourself. Null goes back to the icon and text.
     */
    public void setRowRenderer(RowRenderer<T> rowRenderer) {
        this.rowRenderer = rowRenderer;
    }

    /**
     * Items drawn in the dim text color. None by default.
     */
    public void setDimmed(Predicate<T> dimmed) {
        this.dimmed = dimmed == null ? item -> false : dimmed;
    }

    /**
     * Text shown in the middle while no item is shown, such as "No saves yet". Empty by default.
     */
    public void setEmptyText(String emptyText) {
        this.emptyText = emptyText == null ? "" : emptyText;
    }

    /* Selection */

    public boolean isMultiSelect() {
        return multiSelect;
    }

    /**
     * Whether Ctrl-click and Shift-click can select more than one row. Off by default; turning it off keeps only the lead
     * row selected.
     */
    public void setMultiSelect(boolean multiSelect) {
        this.multiSelect = multiSelect;
        if (!multiSelect && selection.size() > 1) {
            T keep = selected;
            clearSelection();
            if (keep != null) {
                selection.add(keep);
                selected = anchor = keep;
            }
        }
    }

    /**
     * The lead selected item (the last one clicked), or null.
     */
    public T getSelected() {
        return selected;
    }

    public int getSelectedIndex() {
        return indexOf(selected);
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
     * Selects {@code item} and scrolls to it, or clears the selection for null or an item that is not shown. Silent: no
     * {@link #onSelect}.
     */
    public void setSelected(T item) {
        clearSelection();
        int index = indexOf(item);
        if (index < 0) return;
        selection.add(items.get(index));
        selected = anchor = items.get(index);
        scrollToIndex(index);
    }

    public void setSelectedIndex(int index) {
        setSelected(index >= 0 && index < items.size() ? items.get(index) : null);
    }

    /**
     * Selects exactly these items; the ones not shown are skipped. Without {@link #setMultiSelect multi-select} only the
     * last is kept. Silent.
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
     * Selects every shown row, when {@link #setMultiSelect multi-select} is on. Silent.
     */
    public void selectAll() {
        if (!multiSelect || items.isEmpty()) return;
        selection.clear();
        selection.addAll(items);
        selected = items.getLast();
        anchor = items.getFirst();
    }

    /**
     * Silent.
     */
    public void clearSelection() {
        selection.clear();
        selected = anchor = null;
    }

    /**
     * Fired with the new lead item (null when cleared) when the user changes which row is the lead. Not fired by the
     * setters or {@link #refresh}.
     */
    public void onSelect(Consumer<T> onSelect) {
        this.onSelect = onSelect;
    }

    /**
     * Fired with every selected item, top to bottom, when the user changes the selection.
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

    /**
     * Offered every key press and repeat while the list has the keyboard, before its own keys. Use it for keys like Delete.
     */
    public void onKey(KeyListener keyListener) {
        this.keyListener = keyListener;
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
        selection.retainAll(new HashSet<>(items));
        if (selected != null && !selection.contains(selected)) selected = null;
        if (anchor != null && !items.contains(anchor)) anchor = null;
        if (selected == null && !selection.isEmpty()) selected = selection.iterator().next();
        if (selection.isEmpty()) selected = anchor = null;
    }

    private void selectRange(int from, int to, boolean keep) {
        if (!keep) selection.clear();
        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) selection.add(items.get(i));
    }

    /**
     * The modifier keys held now, as GLFW bits. A press carries none, so the list asks the window's input.
     */
    protected int heldModifiers() {
        Window window = getWindow();
        return window == null ? 0 : window.getInput().getModifiers();
    }

    /* Keys */

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
        if (items.isEmpty() || codepoint < 32 || Character.isISOControl(codepoint)) return;
        long now = System.nanoTime();
        if (now - lastTypedNanos > TYPE_AHEAD_NANOS) typed.setLength(0);
        lastTypedNanos = now;
        typed.appendCodePoint(codepoint);
        String prefix = typed.toString().toLowerCase(Locale.ROOT);
        // Typing the same letter again moves on to the next row starting with it, so start the search after the lead row.
        boolean repeat = prefix.codePointCount(0, prefix.length()) > 1 && prefix.chars().allMatch(c -> c == prefix.charAt(0));
        String needle = repeat ? prefix.substring(0, Character.charCount(prefix.codePointAt(0))) : prefix;
        int start = indexOf(selected);
        for (int step = 1; step <= items.size(); step++) {
            int i = Math.floorMod(start + (repeat || prefix.length() == 1 ? step : step - 1), items.size());
            if (labelProvider.apply(items.get(i)).toLowerCase(Locale.ROOT).startsWith(needle)) {
                selectByUser(items.get(i));
                scrollToIndex(i);
                return;
            }
        }
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
        scrollToIndex(index);
    }

    private int pageRows() {
        return Math.max(1, (int) (getHeight() / rowHeight) - 1);
    }

    /* Mouse */

    /**
     * A press anywhere on the list is the list's: it selects a row or drags the scrollbar.
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
        if (draggingThumb) super.onDragUpdate(x, y);
    }

    @Override
    public void onDragEnd(double x, double y) {
        if (draggingThumb) {
            draggingThumb = false;
            super.onDragEnd(x, y);
        }
    }

    /* Geometry */

    /**
     * The shown row under a window y, or -1.
     */
    private int rowAt(double windowY) {
        double viewportY = toLocalY(windowY) - getY();
        int index = (int) Math.floor((viewportY + getScrollOffsetY()) / rowHeight);
        return viewportY >= 0 && index >= 0 && index < items.size() ? index : -1;
    }

    private int indexOf(T item) {
        if (item == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (Objects.equals(items.get(i), item)) return i;
        }
        return -1;
    }

    /**
     * Scrolls the least that brings row {@code index} (of the shown rows) fully into view.
     */
    public void scrollToIndex(int index) {
        if (index < 0 || index >= items.size()) return;
        double top = index * rowHeight, bottom = top + rowHeight, offset = getScrollOffsetY();
        if (top < offset) scrollTo(getScrollOffsetX(), top);
        else if (bottom > offset + getHeight()) scrollTo(getScrollOffsetX(), bottom - getHeight());
    }

    /**
     * Scrolls to {@code item} if it is shown.
     */
    public void scrollToItem(T item) {
        scrollToIndex(indexOf(item));
    }

    private void clampScroll() {
        scrollTo(getScrollOffsetX(), getScrollOffsetY());
    }

    @Override
    public double getMaxScrollY() {
        return Math.max(0, items.size() * rowHeight - getHeight());
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
     * The height of each row. Default 26.
     */
    public void setRowHeight(float rowHeight) {
        this.rowHeight = Math.max(1f, rowHeight);
        clampScroll();
    }

    public float getIconSize() {
        return iconSize;
    }

    /**
     * The width and height of the {@link #setIconProvider icons}. Default 16.
     */
    public void setIconSize(float iconSize) {
        this.iconSize = Math.max(1f, iconSize);
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
     * Whether every other row is drawn slightly darker. Off by default.
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

    /**
     * Pins the text color against theme switches.
     */
    public void setTextColor(Paint color) {
        textColor.set(color);
    }

    public Paint getDimTextColor() {
        return dimTextColor.get();
    }

    public void setDimTextColor(Paint color) {
        dimTextColor.set(color);
    }

    public Paint getSelectionColor() {
        return selectionColor.get();
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

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
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
     * Frees the font. The list can still be drawn afterwards; it makes a new one.
     */
    public void dispose() {
        closeFont();
    }

    /* Rendering */

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
        float scrollY = (float) getScrollOffsetY();
        org.jetbrains.skia.Font f = getFont();
        float textH = FontMetricsCompat.of(f).getHeight();
        Paint text = textColor.get();
        Paint selectionPaint = selectionColor.get();
        Color iconTint = solid(text);

        if (items.isEmpty()) {
            if (!emptyText.isEmpty()) {
                float tw = f.measureTextWidth(emptyText);
                renderer.drawText(emptyText, x + Math.max(TEXT_PADDING, (w - tw) / 2f), y + (rowHeight - textH) / 2f + rowHeight * 0.5f, f, dimTextColor.get());
            }
            return;
        }

        int hoverRow = -1;
        double mx = MouseTracker.getX(), my = MouseTracker.getY();
        if (!draggingThumb && isHovered() && MouseTracker.isInWindow() && contains(mx, my) && !thumbAt(mx, my)) {
            hoverRow = rowAt(my);
        }

        renderer.pushClip(x, y, w, h);
        int first = Math.max(0, (int) (scrollY / rowHeight));
        for (int i = first; i < items.size(); i++) {
            float rowY = y + i * rowHeight - scrollY;
            if (rowY >= y + h) break;
            T item = items.get(i);
            boolean isSelected = selection.contains(item);
            if (striped && i % 2 == 1) {
                renderer.pushAlpha(0.05f);
                renderer.drawQuad(x, rowY, w, rowHeight, text, 0f);
                renderer.popAlpha();
            }
            if (isSelected) {
                if (!focused) renderer.pushAlpha(0.6f);
                renderer.drawQuad(x, rowY, w, rowHeight, selectionPaint, rowCornerRadius);
                if (!focused) renderer.popAlpha();
            } else if (i == hoverRow) {
                renderer.pushAlpha(0.07f);
                renderer.drawQuad(x, rowY, w, rowHeight, text, rowCornerRadius);
                renderer.popAlpha();
            }
            if (rowRenderer != null) {
                renderer.pushClip(x, rowY, w, rowHeight);
                rowRenderer.render(renderer, item, x, rowY, w, rowHeight, isSelected, i == hoverRow);
                renderer.popClip();
                continue;
            }
            boolean dim = dimmed.test(item);
            drawRow(renderer, item, x, rowY, w, textH, f, dim ? dimTextColor.get() : text, iconTint, dim);
        }
        renderer.popClip();
    }

    private void drawRow(Renderer renderer, T item, float x, float rowY, float w, float textH, org.jetbrains.skia.Font f,
                         Paint color, Color iconTint, boolean dim) {
        float left = x + TEXT_PADDING, right = x + w - TEXT_PADDING - (isScrollbarVisible() && getMaxScrollY() > 0 ? 10f : 0f);
        IconAsset icon = iconProvider == null ? null : iconProvider.apply(item);
        if (icon != null) {
            int size = Math.round(iconSize);
            renderer.drawImage(IconCache.loadResource(icon.resourcePath(), size, size), left,
                    rowY + (rowHeight - size) / 2f, size, size, 0f, dim ? 0.45f : 1f, iconTint);
        }
        if (iconProvider != null) left += iconSize + 6f;
        String text = labelProvider.apply(item);
        if (text == null || text.isEmpty() || right <= left) return;
        text = TextFit.ellipsize(text, f, right - left);
        renderer.drawText(text, left, rowY + (rowHeight - textH) / 2f, f, color);
    }

    private static Color solid(Paint paint) {
        return switch (AnimatedPaint.resolve(paint)) {
            case Color c -> c;
            case LinearGradient g -> g.getColors().get(0);
            case null, default -> Color.BLACK;
        };
    }
}
