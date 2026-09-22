package me.piitex.engine.ui.menu;

import me.piitex.engine.Window;
import me.piitex.engine.gl.NativeMenuBar;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.Overlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal strip of menu titles ("File", "Edit", ...) that each open a {@link
 * PopupMenu} underneath. Place it like any Overlay, typically at the top of the root
 * container and as wide as the window, and add menus with {@link #addMenu}.
 * <p>
 * Behaves like a desktop menu bar: click a title to open its menu (click it again, click
 * away, or press Escape to close), then hover across the bar to swap to the neighbouring
 * menu without clicking, or press Left/Right while a menu is open.
 * <p>
 * <b>On macOS the bar is native by default:</b> menus go into the system menu bar at the top of the screen (see
 * {@link NativeMenuBar}) and this overlay draws nothing, takes no clicks and reports a height of 0, so a layout that
 * reads {@link #getHeight()} closes the gap. Everything else keeps working the same: {@link MenuItem} actions,
 * check items, submenus, and enabled/checked changes, which are synced to the native menus automatically. Native
 * menus follow the OS appearance rather than the {@link Theme}, {@link PopupMenu#onOpen} is not called for them,
 * and a shortcut such as {@code "Ctrl+S"} becomes a real Cmd+S key equivalent. Call {@link
 * #setUseNativeMenuBar(boolean) setUseNativeMenuBar(false)} to draw the bar in the window instead. Other platforms
 * always draw in the window.
 * <p>
 * The in-window bar is themed from the container tokens ({@link Theme#getContainerBackground()},
 * {@link Theme#getContainerBorder()}); text uses {@link Theme#getText()}, and the open/
 * hovered title is marked with {@link Theme#getSelection()}. This overlay registers its
 * own {@link #onMouseClick} handler; replacing it stops the bar from responding to
 * clicks.
 */
public class MenuBarOverlay extends Overlay implements Themeable {
    private static final float TITLE_PAD = 12f;

    private final List<String> titles = new ArrayList<>();
    private final List<PopupMenu> menus = new ArrayList<>();
    private float[] titleX = new float[0]; // relative to the bar, computed lazily
    private float[] titleW = new float[0];
    private boolean layoutDirty = true;
    private boolean useNativeMenuBar = true;
    private boolean nativeSubmitted;
    private int openIndex = -1;
    private float fontSize = 15f;
    private final Overridable<Color> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Color> highlightColor = new Overridable<>(new Color(0.60f, 0.78f, 1.0f, 0.55f));
    private final Overridable<Color> dividerColor = new Overridable<>(Color.LIGHT_GRAY);

    private org.jetbrains.skia.Font font;
    private Typeface fontTypeface;

    public MenuBarOverlay(double width, double height) {
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        onMouseClick(event -> {
            int index = titleAt(event.getMouseX());
            if (index >= 0) toggle(index);
        });
        ThemeManager.register(this);
    }

    /**
     * A 28px-tall bar of the given width.
     */
    public MenuBarOverlay(double width) {
        this(width, 28);
    }

    /* Native (system) menu bar */

    /**
     * Whether to use the system menu bar where there is one (macOS). Default true. Turn it off to draw this bar inside
     * the window on every platform, themed like the rest of the UI.
     */
    public void setUseNativeMenuBar(boolean useNativeMenuBar) {
        this.useNativeMenuBar = useNativeMenuBar;
    }

    public boolean isUseNativeMenuBar() {
        return useNativeMenuBar;
    }

    /**
     * True when this bar is currently shown in the system menu bar instead of the window.
     */
    public boolean isNative() {
        return useNativeMenuBar && NativeMenuBar.isSupported();
    }

    /**
     * 0 while {@link #isNative() native}, since the bar takes no room in the window then.
     */
    @Override
    public double getHeight() {
        return isNative() ? 0 : super.getHeight();
    }

    @Override
    public boolean contains(double px, double py) {
        return !isNative() && super.contains(px, py);
    }

    /* Menus */

    /**
     * Adds a menu under {@code title}. Returns {@code menu} for chaining.
     */
    public PopupMenu addMenu(String title, PopupMenu menu) {
        titles.add(title == null ? "" : title);
        menus.add(menu);
        menu.sidewaysListener = this::moveOpenMenu;
        menu.closeListener = () -> {
            if (menus.indexOf(menu) == openIndex) openIndex = -1;
        };
        layoutDirty = true;
        return menu;
    }

    /**
     * Adds a menu under {@code title} built from {@code items}.
     */
    public PopupMenu addMenu(String title, MenuItem... items) {
        return addMenu(title, new PopupMenu(items));
    }

    public List<PopupMenu> getMenus() {
        return java.util.Collections.unmodifiableList(menus);
    }

    /**
     * Index of the menu currently open, or -1.
     */
    public int getOpenIndex() {
        return openIndex;
    }

    public void open(int index) {
        if (index < 0 || index >= menus.size()) return;
        Window window = getWindow();
        if (window == null || isNative()) return;
        ensureLayout();

        PopupMenu menu = menus.get(index);
        // Anchor to the title, but skip the toggle logic, since swapping menus goes through here too.
        menu.show(window, getAbsoluteX() + titleX[index], getAbsoluteY() + getHeight());
        // Set after show(): swapping menus closes the old one (clearing openIndex) while the new one opens.
        if (menu.isOpen()) openIndex = index;
    }

    public void closeMenus() {
        if (openIndex >= 0) menus.get(openIndex).close();
    }

    private void toggle(int index) {
        if (openIndex == index && menus.get(index).isOpen()) {
            closeMenus();
        } else {
            open(index);
        }
    }

    private void moveOpenMenu(int dir) {
        if (openIndex < 0 || menus.isEmpty()) return;
        open(Math.floorMod(openIndex + dir, menus.size()));
    }

    /* Layout */

    private void ensureLayout() {
        if (!layoutDirty && font != null && Font.getDefault() == fontTypeface) return;
        org.jetbrains.skia.Font f = getFont();
        titleX = new float[titles.size()];
        titleW = new float[titles.size()];
        float x = 4f;
        for (int i = 0; i < titles.size(); i++) {
            titleW[i] = f.measureTextWidth(titles.get(i)) + 2 * TITLE_PAD;
            titleX[i] = x;
            x += titleW[i];
        }
        layoutDirty = false;
    }

    /**
     * Index of the title under window X (assuming the pointer's vertical position is already known to be on the bar), or -1.
     */
    private int titleAt(double windowX) {
        ensureLayout();
        double local = windowX - getAbsoluteX();
        for (int i = 0; i < titleX.length; i++) {
            if (local >= titleX[i] && local < titleX[i] + titleW[i]) return i;
        }
        return -1;
    }

    private org.jetbrains.skia.Font getFont() {
        Typeface typeface = Font.getDefault();
        if (font == null || typeface != fontTypeface) {
            if (font != null) font.close();
            font = new org.jetbrains.skia.Font(typeface, fontSize);
            fontTypeface = typeface;
            layoutDirty = true;
        }
        return font;
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        if (font != null) {
            font.close();
            font = null;
        }
        layoutDirty = true;
    }

    public Color getTextColor() {
        return textColor.get();
    }

    /**
     * Pins the title color against theme switches. See {@link Overridable}.
     */
    public void setTextColor(Color color) {
        textColor.set(color);
    }

    /* Theming */

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getContainerBackground());
        getStyling().applyThemeHoverColor(theme.getContainerBackground()); // the bar itself must not tint on hover
        getStyling().applyThemeBorderThickness(0f); // only the bottom divider is drawn, in render()
        getStyling().applyThemeCornerRadius(0f);
        textColor.applyTheme(theme.getText());
        highlightColor.applyTheme(theme.getSelection());
        dividerColor.applyTheme(theme.getContainerBorder());
    }

    /* Update / render */

    @Override
    public void update(float delta) {
        super.update(delta);
        if (isNative()) {
            closeMenus(); // an in-window menu left open by switching modes
            if (getWindow() != null) {
                NativeMenuBar.submit(this, titles, menus);
                nativeSubmitted = true;
            }
            return;
        }
        if (nativeSubmitted) {
            NativeMenuBar.release(this);
            nativeSubmitted = false;
        }
        // Hovering another title while a menu is open swaps to it, like a native menu bar.
        if (openIndex < 0 || !MouseTracker.isInWindow()) return;
        double mx = MouseTracker.getX(), my = MouseTracker.getY();
        if (my < getAbsoluteY() || my > getAbsoluteY() + getHeight()) return;
        int hovered = titleAt(mx);
        if (hovered >= 0 && hovered != openIndex) open(hovered);
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled() || isNative()) return;
        super.render(renderer);

        ensureLayout();
        org.jetbrains.skia.Font f = getFont();
        float x = (float) getX(), y = (float) getY(), h = (float) getHeight();

        int hover = -1;
        if (MouseTracker.isInWindow() && MouseTracker.getY() >= getAbsoluteY() && MouseTracker.getY() <= getAbsoluteY() + getHeight()
                && MouseTracker.getX() >= getAbsoluteX() && MouseTracker.getX() <= getAbsoluteX() + getWidth()) {
            hover = titleAt(MouseTracker.getX());
        }

        float textH = f.getMetrics().getHeight();
        for (int i = 0; i < titles.size(); i++) {
            if (i == openIndex || (i == hover && openIndex < 0)) {
                renderer.drawQuad(x + titleX[i], y + 3f, titleW[i], h - 6f, highlightColor.get(), 5f);
            }
            renderer.drawText(titles.get(i), x + titleX[i] + TITLE_PAD, y + (h - textH) / 2f, f, textColor.get());
        }

        renderer.drawLine(x, y + h - 0.5f, x + (float) getWidth(), y + h - 0.5f, dividerColor.get(), 1f);
    }
}
