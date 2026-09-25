package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A closed box showing the current choice; clicking it opens a floating list of
 * {@code T} items above everything else, the same role JavaFX's {@code ComboBox}/
 * {@code ChoiceBox} plays. The list is shown through {@link Window#showPopup}, so it
 * paints over sibling Elements and Containers, dismisses on an outside click or Escape,
 * and isn't clipped by a parent {@link me.piitex.engine.ui.scroll.ScrollContainer}. It
 * follows the box if the box moves while it is open.
 * <p>
 * The box is themed like {@link TextFieldOverlay}'s field (field background/hover/border
 * tokens, text color from {@link Theme#getText()}); the open list reuses this
 * overlay's own {@link #getStyling()} colors, so pinning them here restyles both, with
 * the {@link Theme#getSelection()} color marking the selected row. Only a plain {@link
 * Color} background/border is honored for the list.
 * <p>
 * This overlay registers its own {@link #onMouseClick} handler to open and close the
 * list; replacing that handler stops the dropdown from opening, so use
 * {@link #onSelect} to react to choices. It is also {@link Focusable}: Tab to it, then
 * Up and Down change the selection and Enter or Space opens the list.
 */
public class DropdownOverlay<T> extends Overlay implements Themeable, Focusable, FontScalable {
    private final List<T> items = new ArrayList<>();
    private int selectedIndex = -1;
    private String placeholder = "Select...";
    private Function<T, String> labelProvider = item -> String.valueOf(item);
    private Consumer<T> onSelect;
    private int maxVisibleRows = 8;
    private float fontSize = 16f;
    private final Overridable<Color> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Color> placeholderColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Color> selectionColor = new Overridable<>(new Color(0.60f, 0.78f, 1.0f, 0.55f));

    private org.jetbrains.skia.Font font;
    private Typeface fontTypeface;
    private ListPopup popup;

    public DropdownOverlay(double width, double height) {
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        setFocusTraversable(true);
        onMouseClick(event -> toggleOpen());
        ThemeManager.register(this);
    }

    /* Items / selection */

    public void addItem(T item) {
        items.add(item);
    }

    /**
     * Replaces every item and clears the selection (without firing {@link #onSelect}).
     */
    public void setItems(Collection<? extends T> newItems) {
        close();
        items.clear();
        items.addAll(newItems);
        selectedIndex = -1;
    }

    public List<T> getItems() {
        return java.util.Collections.unmodifiableList(items);
    }

    /**
     * The chosen item, or null if nothing is selected.
     */
    public T getSelected() {
        return selectedIndex >= 0 && selectedIndex < items.size() ? items.get(selectedIndex) : null;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    /**
     * Selects {@code item} (or clears with null / an unknown item) without firing {@link #onSelect}.
     */
    public void setSelected(T item) {
        selectedIndex = item == null ? -1 : items.indexOf(item);
    }

    /**
     * Selects by index, or clears if out of range, without firing {@link #onSelect}.
     */
    public void setSelectedIndex(int index) {
        selectedIndex = index >= 0 && index < items.size() ? index : -1;
    }

    /**
     * Fired with the new item whenever the USER picks one (click or keyboard), not on programmatic setSelected.
     */
    public void onSelect(Consumer<T> onSelect) {
        this.onSelect = onSelect;
    }

    /**
     * How each item is turned into text. Defaults to {@code String.valueOf}.
     */
    public void setLabelProvider(Function<T, String> labelProvider) {
        this.labelProvider = labelProvider == null ? String::valueOf : labelProvider;
    }

    /**
     * Text shown in the closed box while nothing is selected.
     */
    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
    }

    /**
     * Rows shown before the open list scrolls. Default 8.
     */
    public void setMaxVisibleRows(int maxVisibleRows) {
        this.maxVisibleRows = Math.max(1, maxVisibleRows);
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

    public Color getTextColor() {
        return textColor.get();
    }

    /**
     * Pins the text color against theme switches. See {@link Overridable}.
     */
    public void setTextColor(Color color) {
        textColor.set(color);
    }

    /* Open / close */

    public boolean isOpen() {
        return popup != null;
    }

    public void toggleOpen() {
        if (isOpen()) close();
        else open();
    }

    public void open() {
        if (isOpen() || items.isEmpty()) return;
        Window window = getWindow();
        if (window == null) return;

        ListPopup p = new ListPopup();
        float rowH = (float) getHeight();
        int rows = Math.min(items.size(), maxVisibleRows);
        float h = rows * rowH;
        // The popup is a top-level container, so it is positioned in window coordinates.
        double top = getAbsoluteY();
        float y = (float) (top + getHeight());
        if (y + h > window.getWindowOptions().getHeight() && top - h >= 0) {
            y = (float) top - h; // not enough room below, so open upward
        }
        p.setBounds(getAbsoluteX(), y, getWidth(), h);
        p.scrollToRevealSelected();
        popup = p;
        window.showPopup(p, this, () -> popup = null);
    }

    public void close() {
        if (popup == null) return;
        Window window = getWindow();
        if (window != null) {
            window.closePopup(); // its dismiss callback clears `popup`
        }
        popup = null;
    }

    private void choose(int index) {
        selectedIndex = index;
        if (onSelect != null) onSelect.accept(getSelected());
    }

    /* Theming */

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getFieldBackground());
        getStyling().applyThemeHoverColor(theme.getFieldHoverBackground());
        getStyling().applyThemeBorderColor(theme.getFieldBorder());
        getStyling().applyThemeBorderThickness(theme.getBorderThickness());
        getStyling().applyThemeCornerRadius(theme.getCornerRadius());
        textColor.applyTheme(theme.getText());
        placeholderColor.applyTheme(theme.getPlaceholderText());
        selectionColor.applyTheme(theme.getSelection());
    }

    /* Rendering */

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

    private void drawLabel(Renderer renderer, String text, float x, float y, float h, Paint color) {
        org.jetbrains.skia.Font f = getFont();
        float textH = f.getMetrics().getHeight();
        renderer.drawText(text, x, y + (h - textH) / 2f, f, color);
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);

        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        float pad = 8f;
        T selected = getSelected();
        org.jetbrains.skia.Font f = getFont();
        float maxTextW = w - pad * 2 - h * 0.6f;

        renderer.pushClip(x, y, w - h * 0.6f, h);
        if (selected != null) {
            String label = labelProvider.apply(selected);
            float shift = Math.max(0f, maxTextW - f.measureTextWidth(label)) * textAlignH(0f);
            drawLabel(renderer, label, x + pad + shift, y, h, resolveTextColor(textColor.get()));
        } else {
            drawLabel(renderer, placeholder, x + pad, y, h, placeholderColor.get());
        }
        renderer.popClip();

        // Chevron on the right: down when closed, up when open.
        float cx = x + w - h * 0.45f, cy = y + h / 2f, r = Math.min(h * 0.14f, 5f);
        float dy = isOpen() ? -r * 0.6f : r * 0.6f;
        renderer.drawLine(cx - r, cy - dy, cx, cy + dy, textColor.get(), 2f);
        renderer.drawLine(cx, cy + dy, cx + r, cy - dy, textColor.get(), 2f);
    }

    /* Keyboard */

    @Override
    public void onFocusGained() {
    }

    @Override
    public void onFocusLost() {
    }

    @Override
    public void onCharTyped(int codepoint) {
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action == GLFW.GLFW_RELEASE) return;
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            if (action == GLFW.GLFW_PRESS) toggleOpen();
        } else if ((key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) && !items.isEmpty()) {
            int next = selectedIndex + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
            if (next >= 0 && next < items.size()) choose(next);
        }
    }

    /**
     * The floating list. A Container (so {@link Window#showPopup} and the input
     * system's hit-testing treat it like any top-level Container) that draws and
     * hit-tests its rows itself rather than holding child Elements.
     */
    private class ListPopup extends Container implements Scrollable {
        private float scroll; // in rows' worth of pixels, 0 = top

        ListPopup() {
            super(0, 0);
            setCursorMode(CursorMode.POINTER);
            onMouseClick(event -> {
                int index = rowAt(event.getMouseY());
                if (index >= 0 && index < items.size()) {
                    choose(index);
                    close();
                }
            });
        }

        private float rowHeight() {
            return (float) DropdownOverlay.this.getHeight();
        }

        private float maxScroll() {
            return Math.max(0f, items.size() * rowHeight() - (float) getHeight());
        }

        private int rowAt(double mouseY) {
            return (int) Math.floor((mouseY - getY() + scroll) / rowHeight());
        }

        void scrollToRevealSelected() {
            if (selectedIndex < 0) return;
            float top = selectedIndex * rowHeight();
            float bottom = top + rowHeight();
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
            DropdownOverlay<T> owner = DropdownOverlay.this;
            float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
            float rowH = rowHeight();
            float radius = owner.getStyling().getCornerRadius();
            Paint bg = owner.getStyling().getBackgroundColor();
            Paint hoverPaint = owner.getStyling().getHoverColor();
            Paint border = owner.getStyling().getBorderColor();

            if (bg instanceof Color c) renderer.drawQuad(x, y, w, h, c, radius);

            renderer.pushClip(x, y, w, h);
            int hoverRow = MouseTracker.isInWindow() && contains(MouseTracker.getX(), MouseTracker.getY())
                    ? rowAt(MouseTracker.getY()) : -1;
            int first = Math.max(0, (int) (scroll / rowH));
            for (int i = first; i < items.size(); i++) {
                float rowY = y + i * rowH - scroll;
                if (rowY >= y + h) break;
                if (i == selectedIndex) {
                    renderer.drawQuad(x, rowY, w, rowH, owner.selectionColor.get(), 0f);
                } else if (i == hoverRow && hoverPaint instanceof Color hc) {
                    renderer.drawQuad(x, rowY, w, rowH, hc, 0f);
                }
                owner.drawLabel(renderer, owner.labelProvider.apply(items.get(i)), x + 8f, rowY, rowH, owner.textColor.get());
            }
            renderer.popClip();

            if (border instanceof Color bc && owner.getStyling().getBorderThickness() > 0) {
                renderer.drawBorder(x, y, w, h, bc, owner.getStyling().getBorderThickness(), radius);
            }
        }
    }
}
