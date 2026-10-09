package me.piitex.engine.ui.menu;

import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.Window;
import me.piitex.engine.gl.NativeMenuBar;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.Binding;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.input.ShortcutHandler;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.overlays.Overlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

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
 * hovered title is marked with {@link Theme#getSelection()}. Its own click handling is kept
 * apart from {@link #onMouseClick}, so setting one does not stop the bar from responding to clicks.
 */
public class MenuBarOverlay extends Overlay implements Themeable, ShortcutHandler {
    private static final float TITLE_PAD = 12f;

    private final List<String> titles = new ArrayList<>();
    private final List<PopupMenu> menus = new ArrayList<>();
    private final List<Character> mnemonics = new ArrayList<>();
    private Window shortcutWindow;
    private float[] titleX = new float[0]; // relative to the bar, computed lazily
    private float[] titleW = new float[0];
    private boolean layoutDirty = true;
    private boolean useNativeMenuBar = true;
    private boolean nativeSubmitted;
    private int openIndex = -1;
    private float fontSize = 15f;
    private final Overridable<Paint> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Paint> highlightColor = new Overridable<>(new Color(0.60f, 0.78f, 1.0f, 0.55f));
    private final Overridable<Paint> dividerColor = new Overridable<>(Color.LIGHT_GRAY);

    private org.jetbrains.skia.Font font;
    private Typeface fontTypeface;

    public MenuBarOverlay(double width, double height) {
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        setBuiltInClickListener(event -> {
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
        mnemonics.add('\0');
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

    /**
     * Makes {@code mnemonic} open menu {@code index} when pressed with Alt, such as {@code 'F'} for File, and
     * underlines it in the title (the first place it appears, ignoring case). 0 removes it. Only the in-window bar
     * uses it.
     */
    public void setMnemonic(int index, char mnemonic) {
        if (index < 0 || index >= menus.size()) throw new IndexOutOfBoundsException("no menu " + index);
        mnemonics.set(index, mnemonic);
    }

    public char getMnemonic(int index) {
        return mnemonics.get(index);
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
        menu.show(this, getAbsoluteX() + titleX[index], getAbsoluteY() + getHeight());
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

    /* Shortcuts */

    /**
     * Binds the keys. A {@link MenuItem#setShortcut shortcut} such as {@code "Ctrl+S"} chooses its item when pressed
     * anywhere in the window, as long as the item is enabled and no popup or modal is open; the menu's
     * {@link PopupMenu#onOpen} runs first so the item's state is current. {@code Ctrl} also means Command on a Mac.
     * A focused text field keeps its own select all, copy, cut, paste, undo and redo keys. F10 opens the first menu
     * with the keyboard, and Alt with a {@link #setMnemonic mnemonic} opens its menu. The system menu bar on macOS
     * does all of this itself.
     */
    @Override
    public boolean onShortcut(int key, int mods) {
        if (isNative() || !isEnabled() || isEffectivelyDisabled() || getWindow() == null) return false;
        mods &= Binding.MODIFIER_MASK; // Caps Lock and Num Lock do not count

        if (key == GLFW.GLFW_KEY_F10 && mods == 0 && !menus.isEmpty()) {
            open(0);
            menus.get(0).highlightFirst();
            return true;
        }
        if (mods == GLFW.GLFW_MOD_ALT) {
            for (int i = 0; i < menus.size(); i++) {
                char m = mnemonics.get(i);
                if (m != 0 && keyOfMnemonic(m) == key) {
                    open(i);
                    menus.get(i).highlightFirst();
                    return true;
                }
            }
        }
        for (PopupMenu menu : menus) {
            MenuItem item = find(menu.getItems(), key, mods);
            if (item == null) continue;
            menu.refresh(); // enabled state and even the items may have changed
            item = find(menu.getItems(), key, mods);
            if (item == null || !item.isEnabled()) return item != null; // the combination is theirs even when it is off
            item.fire();
            return true;
        }
        return false;
    }

    private static int keyOfMnemonic(char c) {
        char upper = Character.toUpperCase(c);
        if (upper >= 'A' && upper <= 'Z') return GLFW.GLFW_KEY_A + (upper - 'A');
        if (upper >= '0' && upper <= '9') return GLFW.GLFW_KEY_0 + (upper - '0');
        return -1;
    }

    /** The item with a shortcut that is this key press, looking inside submenus too. */
    private static MenuItem find(List<MenuItem> items, int key, int mods) {
        for (MenuItem item : items) {
            if (item.isSeparator()) continue;
            if (item.hasSubmenu()) {
                MenuItem inner = find(item.getChildren(), key, mods);
                if (inner != null) return inner;
                continue;
            }
            MenuShortcut shortcut = MenuShortcut.parse(item.getShortcut());
            if (shortcut != null && shortcut.matches(key, mods)) return item;
        }
        return null;
    }

    private void syncShortcutWindow() {
        Window window = isNative() ? null : getWindow();
        if (window == shortcutWindow) return;
        if (shortcutWindow != null) shortcutWindow.removeShortcutHandler(this);
        shortcutWindow = window;
        if (window != null) window.addShortcutHandler(this);
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

    private int mnemonicIndex(int i) {
        char m = mnemonics.get(i);
        return m == 0 ? -1 : titles.get(i).toLowerCase().indexOf(Character.toLowerCase(m));
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

    public Paint getTextColor() {
        return textColor.get();
    }

    /**
     * Pins the title color against theme switches. See {@link Overridable}.
     */
    public void setTextColor(Paint color) {
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

    @Override
    public void followTheme() {
        super.followTheme();
        textColor.clearExplicit();
        highlightColor.clearExplicit();
        dividerColor.clearExplicit();
    }

    /* Update / render */

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(highlightColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(dividerColor.get(), delta);
        syncShortcutWindow();
        if (isNative()) {
            closeMenus(); // an in-window menu left open by switching modes
            if (getWindow() != null) {
                NativeMenuBar.submit(this, getWindow().isFocused(), titles, menus);
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

        float textH = FontMetricsCompat.of(f).getHeight();
        for (int i = 0; i < titles.size(); i++) {
            if (i == openIndex || (i == hover && openIndex < 0)) {
                renderer.drawQuad(x + titleX[i], y + 3f, titleW[i], h - 6f, highlightColor.get(), 5f);
            }
            float titleLeft = x + titleX[i] + TITLE_PAD, titleTop = y + (h - textH) / 2f;
            renderer.drawText(titles.get(i), titleLeft, titleTop, f, textColor.get());
            int mnemonic = mnemonicIndex(i);
            if (mnemonic >= 0) {
                String title = titles.get(i);
                float ux = titleLeft + f.measureTextWidth(title.substring(0, mnemonic));
                float uw = f.measureTextWidth(title.substring(mnemonic, mnemonic + 1));
                renderer.drawLine(ux, titleTop + textH + 1f, ux + uw, titleTop + textH + 1f, textColor.get(), 1f);
            }
        }

        renderer.drawLine(x, y + h - 0.5f, x + (float) getWidth(), y + h - 0.5f, dividerColor.get(), 1f);
    }
}
