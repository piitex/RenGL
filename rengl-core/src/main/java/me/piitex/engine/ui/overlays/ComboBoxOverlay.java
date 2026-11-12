package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A text field with a list of suggestions under it: the user types, the list narrows to the items that match, and picking one
 * fills the field. Unlike a {@link DropdownOverlay}, which only lets the user choose from its items, a combo box also takes
 * text that is not in the list, so it suits a search box, a command line with completions, or a "city" field that knows the
 * common ones.
 * <pre>{@code
 * ComboBoxOverlay<String> city = new ComboBoxOverlay<>(240, 32);
 * city.setItems(List.of("Amsterdam", "Berlin", "Bern", "Boston"));
 * city.setPlaceholder("City");
 * city.onItemSelect(name -> weather.show(name));
 * }</pre>
 * It is a {@link TextFieldOverlay}, so everything a field does (placeholder, max length, {@link #onTextChanged}, {@link
 * #onSubmit}, undo, a password mask) works the same. The list opens as the user types, on Down, or on a click on the arrow at
 * the right end, and is shown through the window's popup layer like a dropdown's, so it paints over its neighbours and
 * closes on an outside click or Escape. Up and Down move the highlighted row, Enter picks it (or submits the text when none
 * is highlighted), and the wheel scrolls a long list.
 * <p>
 * Which items match is {@link #setMatcher}: by default, those whose text contains what was typed, ignoring case. The list is
 * drawn with the field's colors; the {@link #getSelectionColor() selection color} marks the highlighted row.
 */
public class ComboBoxOverlay<T> extends TextFieldOverlay {
    private final List<T> items = new ArrayList<>();
    private Function<T, String> labelProvider = String::valueOf;
    private BiPredicate<T, String> matcher = (item, typed) -> true; // replaced in the constructor, which needs labelProvider
    private int maxVisibleRows = 8;
    private boolean openOnFocus;
    private Consumer<T> onItemSelect;

    private T picked;
    private List<T> suggestions = List.of();
    private int highlighted = -1;
    private SuggestionPopup popup;
    private boolean picking; // giving the keyboard back after a pick

    private org.jetbrains.skia.Font rowFont;
    private Typeface rowTypeface;
    private float rowFontSize;

    public ComboBoxOverlay(double width, double height) {
        super(width, height);
        setCursorMode(CursorMode.TEXT);
        this.matcher = (item, typed) -> labelOf(item).toLowerCase(Locale.ROOT).contains(typed.toLowerCase(Locale.ROOT));
    }

    /* Items */

    public void addItem(T item) {
        items.add(item);
        if (isOpen()) refreshSuggestions(false);
    }

    /**
     * Replaces every item. The text in the field is left as it is.
     */
    public void setItems(Collection<? extends T> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        picked = null;
        if (isOpen()) refreshSuggestions(false);
    }

    public List<T> getItems() {
        return java.util.Collections.unmodifiableList(items);
    }

    /**
     * How an item becomes the text it matches and fills the field with. {@code String.valueOf} by default.
     */
    public void setLabelProvider(Function<T, String> labelProvider) {
        this.labelProvider = labelProvider == null ? String::valueOf : labelProvider;
    }

    /**
     * Which items the list shows for the text typed so far, as {@code (item, typed)}. Default: the label contains the text,
     * ignoring case. Use {@code startsWith} for a strict prefix match, or anything else, such as a fuzzy score. The text is
     * never empty here.
     */
    public void setMatcher(BiPredicate<T, String> matcher) {
        Objects.requireNonNull(matcher, "matcher");
        this.matcher = matcher;
    }

    /**
     * Rows shown before the list scrolls. Default 8.
     */
    public void setMaxVisibleRows(int maxVisibleRows) {
        this.maxVisibleRows = Math.max(1, maxVisibleRows);
    }

    public boolean isOpenOnFocus() {
        return openOnFocus;
    }

    /**
     * Whether the list opens, showing every item, as soon as the field is clicked, not only after typing. Off by default.
     */
    public void setOpenOnFocus(boolean openOnFocus) {
        this.openOnFocus = openOnFocus;
    }

    /**
     * Fired with the item the user picks from the list, by click or Enter, after the field has been filled with its text.
     * Not fired for text typed in full, nor by {@link #setSelectedItem}.
     */
    public void onItemSelect(Consumer<T> onItemSelect) {
        this.onItemSelect = onItemSelect;
    }

    /**
     * The item the field's text names: the one picked from the list or set with {@link #setSelectedItem}, as long as the
     * text still equals its label, otherwise the first item whose label equals the text exactly, otherwise null (the text is
     * free).
     */
    public T getSelectedItem() {
        String current = getText();
        if (picked != null && labelOf(picked).equals(current)) return picked;
        for (T item : items) {
            if (labelOf(item).equals(current)) return item;
        }
        return null;
    }

    /**
     * Fills the field with {@code item}'s label, or empties it for null. Silent: no {@link #onItemSelect}, no list.
     */
    public void setSelectedItem(T item) {
        close();
        picked = item;
        setText(item == null ? "" : labelOf(item));
    }

    private String labelOf(T item) {
        String label = labelProvider.apply(item);
        return label == null ? "" : label;
    }

    /* The list */

    public boolean isOpen() {
        return popup != null;
    }

    /**
     * Opens the list with every item, as the arrow does, or closes it when there is nothing to show.
     */
    public void open() {
        refreshSuggestions(true);
    }

    public void close() {
        if (popup == null) return;
        SuggestionPopup closing = popup;
        popup = null;
        highlighted = -1;
        PopupHost host = getPopupHost();
        if (host != null && host.getPopup() == closing) host.closePopup();
    }

    /**
     * The items the open list shows, top to bottom. Empty when it is closed.
     */
    public List<T> getSuggestions() {
        return popup == null ? List.of() : List.copyOf(suggestions);
    }

    /**
     * The highlighted row of the open list, or -1.
     */
    public int getHighlightedIndex() {
        return highlighted;
    }

    private void refreshSuggestions(boolean all) {
        String typed = getText();
        List<T> next = new ArrayList<>();
        for (T item : items) {
            if (all || typed.isEmpty() || matcher.test(item, typed)) next.add(item);
        }
        // Nothing to offer, or the only offer is exactly what is typed.
        boolean nothing = next.isEmpty() || (!all && next.size() == 1 && labelOf(next.getFirst()).equals(typed));
        if (nothing) {
            close();
            return;
        }
        T keep = highlighted >= 0 && highlighted < suggestions.size() ? suggestions.get(highlighted) : null;
        suggestions = next;
        highlighted = keep == null ? -1 : next.indexOf(keep);
        show();
    }

    private void show() {
        PopupHost host = getPopupHost();
        if (host == null) return;
        SuggestionPopup p = new SuggestionPopup();
        float rowH = rowHeight();
        float h = Math.min(suggestions.size(), maxVisibleRows) * rowH;
        double[] area = host.getPopupArea();
        double top = getAbsoluteY();
        float y = (float) (top + getHeight());
        if (y + h > area[1] + area[3] && top - h >= area[1]) y = (float) top - h; // no room below, so open upward
        p.setBounds(getAbsoluteX(), y, getWidth(), h);
        p.reveal(highlighted);
        // showPopup closes the old list first, which runs its dismiss callback and clears the highlight; put both back after.
        int keep = highlighted;
        host.showPopup(p, this, () -> {
            if (popup == p) {
                popup = null;
                highlighted = -1;
            }
        });
        popup = p;
        highlighted = keep;
    }

    private float rowHeight() {
        return (float) Math.max(24.0, getHeight());
    }

    private void pick(int index) {
        if (index < 0 || index >= suggestions.size()) return;
        T item = suggestions.get(index);
        close();
        picked = item;
        setText(labelOf(item));
        // A click on the list took the keyboard from the field; give it back, without reopening the list.
        PopupHost host = getPopupHost();
        if (host != null && host.getFocusedElement() != this) {
            picking = true;
            try {
                host.requestFocus(this);
            } finally {
                picking = false;
            }
        }
        if (onItemSelect != null) onItemSelect.accept(item);
    }

    /* Input */

    @Override
    public void onFocusGained() {
        super.onFocusGained();
        if (openOnFocus && !picking && !isOpen()) refreshSuggestions(true);
    }

    @Override
    public void onFocusLost() {
        super.onFocusLost();
        // Clicking the list takes the keyboard from the field, which must not close the list; tabbing away does.
        boolean overList = MouseTracker.isInWindow() && popup != null && popup.contains(MouseTracker.getX(), MouseTracker.getY());
        if (isOpen() && !overList) close();
    }

    @Override
    public void onCharTyped(int codepoint) {
        String before = getText();
        super.onCharTyped(codepoint);
        if (!before.equals(getText())) refreshSuggestions(false);
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action == GLFW.GLFW_RELEASE) return;
        if (isOpen()) {
            switch (key) {
                case GLFW.GLFW_KEY_DOWN -> {
                    highlighted = Math.min(suggestions.size() - 1, highlighted + 1);
                    popup.reveal(highlighted);
                    return;
                }
                case GLFW.GLFW_KEY_UP -> {
                    highlighted = highlighted <= 0 ? -1 : highlighted - 1;
                    if (highlighted >= 0) popup.reveal(highlighted);
                    return;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (highlighted >= 0) {
                        if (action == GLFW.GLFW_PRESS) pick(highlighted);
                        return;
                    }
                    close(); // no row chosen: Enter submits what was typed
                }
                default -> {
                }
            }
        } else if (key == GLFW.GLFW_KEY_DOWN && (mods & GLFW.GLFW_MOD_SHIFT) == 0) {
            refreshSuggestions(false);
            if (!isOpen()) refreshSuggestions(true); // the text matches nothing else: offer the whole list
            if (isOpen()) {
                highlighted = 0;
                popup.reveal(0);
            }
            return;
        }
        String before = getText();
        super.onKeyPressed(key, action, mods);
        if (!before.equals(getText())) refreshSuggestions(false);
    }

    /**
     * A press on the arrow at the right end opens (or, if already open, the outside-press that precedes this closes) the
     * list of every item instead of placing the caret.
     */
    @Override
    public void onDragStart(double x, double y) {
        if (toLocalX(x) >= getX() + getWidth() - arrowZone()) {
            refreshSuggestions(true);
            return;
        }
        super.onDragStart(x, y);
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (toLocalX(x) < getX() + getWidth() - arrowZone()) super.onDragUpdate(x, y);
    }

    private float arrowZone() {
        return (float) Math.min(getWidth() / 3, getHeight());
    }

    @Override
    public CursorMode getCursorMode() {
        if (MouseTracker.isInWindow() && contains(MouseTracker.getX(), MouseTracker.getY())
                && toLocalX(MouseTracker.getX()) >= getX() + getWidth() - arrowZone()) {
            return CursorMode.POINTER;
        }
        return super.getCursorMode();
    }

    /* Rendering */

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);
        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        float cx = x + w - h * 0.45f, cy = y + h / 2f, r = Math.min(h * 0.14f, 5f);
        float dy = isOpen() ? -r * 0.6f : r * 0.6f;
        renderer.drawLine(cx - r, cy - dy, cx, cy + dy, getTextColor(), 2f);
        renderer.drawLine(cx, cy + dy, cx + r, cy - dy, getTextColor(), 2f);
    }

    private org.jetbrains.skia.Font getRowFont() {
        Typeface typeface = Font.getDefault();
        if (rowFont == null || typeface != rowTypeface || rowFontSize != getFontSize()) {
            closeRowFont();
            rowFont = new org.jetbrains.skia.Font(typeface, getFontSize());
            rowTypeface = typeface;
            rowFontSize = getFontSize();
        }
        return rowFont;
    }

    private void closeRowFont() {
        if (rowFont != null) {
            rowFont.close();
            rowFont = null;
        }
    }

    @Override
    public void dispose() {
        close();
        closeRowFont();
        super.dispose();
    }

    /**
     * The floating list. A Container that draws and hit-tests its rows itself, as {@link DropdownOverlay}'s does, but does not
     * take the keyboard: keys keep going to the field.
     */
    private class SuggestionPopup extends Container implements Scrollable {
        private float scroll;

        SuggestionPopup() {
            super(0, 0);
            setCursorMode(CursorMode.POINTER);
            onMouseClick(event -> pick(rowAt(event.getMouseY())));
        }

        private int rowAt(double mouseY) {
            int index = (int) Math.floor((mouseY - getY() + scroll) / rowHeight());
            return index >= 0 && index < suggestions.size() ? index : -1;
        }

        private float maxScroll() {
            return Math.max(0f, suggestions.size() * rowHeight() - (float) getHeight());
        }

        /**
         * Scrolls the least that shows row {@code index}; none for -1.
         */
        void reveal(int index) {
            if (index < 0) return;
            float top = index * rowHeight(), bottom = top + rowHeight();
            if (top < scroll) scroll = top;
            else if (bottom > scroll + (float) getHeight()) scroll = bottom - (float) getHeight();
            scroll = Math.max(0f, Math.min(scroll, maxScroll()));
        }

        @Override
        public void onScroll(double deltaX, double deltaY) {
            scroll = Math.max(0f, Math.min(scroll - (float) deltaY * rowHeight(), maxScroll()));
        }

        @Override
        public void render(Renderer renderer) {
            ComboBoxOverlay<T> owner = ComboBoxOverlay.this;
            float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
            float rowH = rowHeight();
            float radius = owner.getStyling().getCornerRadius();
            Paint hoverPaint = owner.getStyling().getHoverColor();
            Paint text = owner.getTextColor();
            org.jetbrains.skia.Font f = owner.getRowFont();
            float textH = FontMetricsCompat.of(f).getHeight();

            renderer.drawQuad(x, y, w, h, owner.getStyling().getBackgroundColor(), radius);
            renderer.pushClip(x, y, w, h, radius);
            double mx = MouseTracker.getX(), my = MouseTracker.getY();
            int hoverRow = MouseTracker.isInWindow() && contains(mx, my) ? rowAt(my) : -1;
            int first = Math.max(0, (int) (scroll / rowH));
            for (int i = first; i < suggestions.size(); i++) {
                float rowY = y + i * rowH - scroll;
                if (rowY >= y + h) break;
                if (i == highlighted) renderer.drawQuad(x, rowY, w, rowH, owner.getSelectionColor(), 0f);
                else if (i == hoverRow) renderer.drawQuad(x, rowY, w, rowH, hoverPaint, 0f);
                renderer.drawText(owner.labelOf(suggestions.get(i)), x + 8f, rowY + (rowH - textH) / 2f, f, text);
            }
            if (maxScroll() > 0f) {
                float track = h - 4f, thumb = Math.max(18f, track * h / (suggestions.size() * rowH));
                float thumbY = y + 2f + (track - thumb) * (scroll / maxScroll());
                renderer.pushAlpha(0.45f);
                renderer.drawQuad(x + w - 6f, thumbY, 3f, thumb, text, 1.5f);
                renderer.popAlpha();
            }
            renderer.popClip();
            renderer.drawBorder(x, y, w, h, owner.getStyling().getBorderColor(), owner.getStyling().getBorderThickness(), radius);
        }
    }

    // --- Accessibility ---

    @Override
    protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
        return me.piitex.engine.access.AccessibleRole.COMBO_BOX;
    }
}
