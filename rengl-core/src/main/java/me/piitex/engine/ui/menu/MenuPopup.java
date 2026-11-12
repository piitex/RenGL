package me.piitex.engine.ui.menu;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.PopupKeyListener;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.image.IconCache;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.text.Font;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The single popup Container behind a {@link PopupMenu}: it owns the root panel and every
 * open submenu panel, draws and hit-tests them itself (no child Elements, like the
 * dropdown's list), and reports {@link #contains} for the union of its panels so the
 * window's outside-click dismissal treats a click on any submenu as "inside".
 */
final class MenuPopup extends Container implements PopupKeyListener, Scrollable {
    private static final float PAD_Y = 4f;         // panel top/bottom padding
    private static final float PAD_X = 4f;         // gap between panel edge and highlight
    private static final float TEXT_PAD = 10f;     // inside the highlight, before the label
    private static final float CHECK_COL = 20f;
    private static final float ICON_COL = 24f;
    private static final float ICON_SIZE = 16f;
    private static final float WINDOW_EDGE = 4f;       // room kept between a scrolling menu and the window's edge
    private static final float WHEEL_STEP = 40f;
    private static final float ARROW_COL = 18f;
    private static final float SHORTCUT_GAP = 28f;
    private static final float SEPARATOR_H = 9f;
    private static final float OPEN_DELAY = 0.12f;   // hover a submenu row this long before it opens
    private static final float SWITCH_DELAY = 0.22f; // hover a sibling row this long before an open submenu closes

    private static final class Panel {
        final List<MenuItem> items;
        float x, y, w, h;
        float[] rowTop;   // relative to y
        float[] rowH;
        boolean checkCol, iconCol, shortcutCol, arrowCol;
        float contentH;   // all the rows; h is what shows of it, and scroll is how far down that starts
        float scroll;
        float labelW, shortcutW;
        /**
         * The row whose submenu is open, or the keyboard-highlighted row; -1 for none.
         */
        int active = -1;

        Panel(List<MenuItem> items) {
            this.items = items;
        }

        boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        /**
         * Row index under window point (px, py), or -1 for padding/outside.
         */
        int rowAt(double px, double py) {
            if (!contains(px, py)) return -1;
            double local = py - y + scroll;
            for (int i = 0; i < rowTop.length; i++) {
                if (local >= rowTop[i] && local < rowTop[i] + rowH[i]) return i;
            }
            return -1;
        }
    }

    private final PopupMenu menu;
    private final PopupHost win;
    private final List<Panel> panels = new ArrayList<>();
    private final org.jetbrains.skia.Font font;
    private boolean closed;

    /**
     * True after a key press, until the mouse moves again: the keyboard highlight then wins over hover.
     */
    private boolean keyboardMode;
    private double lastMouseX = Double.NaN, lastMouseY = Double.NaN;
    private int pendingPanel = -1, pendingRow = -1;
    private float pendingTime;
    /** What is said when the menu appears, folded into the first row's reading when a key gets there first. */
    private String opening;
    private int spokenPanel = -1, spokenRow = -1;

    MenuPopup(PopupMenu menu, PopupHost win, double x, double y) {
        super(0, 0);
        this.menu = menu;
        this.win = win;
        this.font = new org.jetbrains.skia.Font(Font.getDefault(), menu.getFontSize());
        setCursorMode(CursorMode.POINTER);

        panels.add(layout(menu.getItems(), x, y));
        syncBounds();
        opening = openingText();
        lastMouseX = MouseTracker.getX();
        lastMouseY = MouseTracker.getY();

        onMouseClick(event -> {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            for (int p = panels.size() - 1; p >= 0; p--) {
                int row = panels.get(p).rowAt(event.getMouseX(), event.getMouseY());
                if (row >= 0) {
                    activate(p, row);
                    return;
                }
            }
        });
    }

    /**
     * The panels hold window coordinates rather than being children, so moving the popup
     * (as {@link Window} does to keep it with its anchor) moves them by the same amount.
     * This Container's box is kept as the union of the panels; see {@link #syncBounds()}.
     */
    @Override
    public void setX(double x) {
        float dx = (float) (x - getX());
        if (panels != null) for (Panel panel : panels) panel.x += dx; // null while the super constructor runs
        super.setX(x);
    }

    @Override
    public void setY(double y) {
        float dy = (float) (y - getY());
        if (panels != null) for (Panel panel : panels) panel.y += dy;
        super.setY(y);
    }

    /* Layout */

    private Panel layout(List<MenuItem> items, double x, double y) {
        Panel panel = new Panel(items);
        int n = items.size();
        panel.rowTop = new float[n];
        panel.rowH = new float[n];

        float top = PAD_Y;
        for (int i = 0; i < n; i++) {
            MenuItem item = items.get(i);
            panel.rowTop[i] = top;
            panel.rowH[i] = item.isSeparator() ? SEPARATOR_H : menu.getItemHeight();
            top += panel.rowH[i];
            if (item.isSeparator()) continue;
            panel.labelW = Math.max(panel.labelW, TextShaping.width(font, item.getLabel()));
            if (item.isCheckable()) panel.checkCol = true;
            if (item.getIcon() != null) panel.iconCol = true;
            if (item.hasSubmenu()) panel.arrowCol = true;
            if (item.getShortcut() != null && !item.getShortcut().isEmpty()) {
                panel.shortcutCol = true;
                panel.shortcutW = Math.max(panel.shortcutW, TextShaping.width(font, item.getShortcut()));
            }
        }
        panel.contentH = top + PAD_Y;
        panel.w = Math.max(menu.getMinWidth(), 2 * PAD_X + 2 * TEXT_PAD
                + (panel.checkCol ? CHECK_COL : 0f)
                + (panel.iconCol ? ICON_COL : 0f)
                + panel.labelW
                + (panel.shortcutCol ? SHORTCUT_GAP + panel.shortcutW : 0f)
                + (panel.arrowCol ? ARROW_COL : 0f));

        double[] area = win.getPopupArea();
        double winW = area[2], winH = area[3];
        // A menu taller than the window fills the window's height and scrolls.
        panel.h = (float) Math.min(panel.contentH, Math.max(winH - 2 * WINDOW_EDGE, 2 * menu.getItemHeight()));
        panel.x = (float) Math.max(area[0], Math.min(x, area[0] + winW - panel.w));
        panel.y = (float) Math.max(area[1], Math.min(y, area[1] + winH - panel.h));
        return panel;
    }

    /**
     * Opens the submenu of {@code row} in panel {@code p} beside it, closing anything deeper.
     */
    private void openChild(int p, int row, boolean selectFirst) {
        truncate(p + 1);
        spokenPanel = -1;
        Panel parent = panels.get(p);
        parent.active = row;
        MenuItem item = parent.items.get(row);
        if (!item.hasSubmenu()) return;

        double[] area = win.getPopupArea();
        double areaRight = area[0] + area[2];
        double y = parent.y + parent.rowTop[row] - parent.scroll - PAD_Y;
        Panel child = layout(item.getChildren(), parent.x + parent.w - 2, y);
        if (parent.x + parent.w - 2 + child.w > areaRight) {
            child.x = (float) Math.max(area[0], parent.x - child.w + 2); // no room on the right, so flip to the left
        }
        if (selectFirst) child.active = step(child, -1, 1);
        panels.add(child);
        syncBounds();
    }

    private void truncate(int size) {
        while (panels.size() > size) panels.remove(panels.size() - 1);
        syncBounds();
    }

    /**
     * Makes this Container's own box the union of the open panels, so {@link Window} can keep the whole menu inside
     * the window when it moves it. Goes through super so the panels themselves are not shifted.
     */
    private void syncBounds() {
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (Panel panel : panels) {
            left = Math.min(left, panel.x);
            top = Math.min(top, panel.y);
            right = Math.max(right, panel.x + panel.w);
            bottom = Math.max(bottom, panel.y + panel.h);
        }
        if (panels.isEmpty()) return;
        super.setX(left);
        super.setY(top);
        setWidth(right - left);
        setHeight(bottom - top);
    }

    /* Selection */

    private static boolean selectable(MenuItem item) {
        return !item.isSeparator() && item.isEnabled();
    }

    /**
     * The next selectable row from {@code from} in direction {@code dir} (wrapping), or -1 if there is none.
     */
    private static int step(Panel panel, int from, int dir) {
        int n = panel.items.size();
        if (n == 0) return -1;
        int i = from;
        for (int tries = 0; tries < n; tries++) {
            i = i < 0 ? (dir > 0 ? 0 : n - 1) : Math.floorMod(i + dir, n);
            if (selectable(panel.items.get(i))) return i;
        }
        return -1;
    }

    /**
     * Scrolls panel {@code panel} just far enough to show row {@code row}.
     */
    private static void reveal(Panel panel, int row) {
        if (row < 0 || panel.contentH <= panel.h) return;
        float top = panel.rowTop[row] - (row == 0 ? PAD_Y : 0f); // the first row brings its padding with it
        float bottom = panel.rowTop[row] + panel.rowH[row] + (row == panel.items.size() - 1 ? PAD_Y : 0f);
        if (top < panel.scroll) panel.scroll = top;
        else if (bottom > panel.scroll + panel.h) panel.scroll = bottom - panel.h;
        panel.scroll = Math.clamp(panel.scroll, 0f, panel.contentH - panel.h);
    }

    @Override
    public void onScroll(double deltaX, double deltaY) {
        Panel panel = panelUnderPointer();
        if (panel == null || panel.contentH <= panel.h) return;
        panel.scroll = Math.clamp(panel.scroll - (float) deltaY * WHEEL_STEP, 0f, panel.contentH - panel.h);
    }

    @Override
    public boolean canScroll(double deltaX, double deltaY) {
        Panel panel = panelUnderPointer();
        if (panel == null || panel.contentH <= panel.h) return false;
        return deltaY > 0 ? panel.scroll > 0 : panel.scroll < panel.contentH - panel.h;
    }

    private Panel panelUnderPointer() {
        double mx = MouseTracker.getX(), my = MouseTracker.getY();
        for (int p = panels.size() - 1; p >= 0; p--) {
            if (panels.get(p).contains(mx, my)) return panels.get(p);
        }
        return null;
    }

    private void activate(int p, int row) {
        MenuItem item = panels.get(p).items.get(row);
        if (!selectable(item)) return;
        if (item.hasSubmenu()) {
            openChild(p, row, false);
            return;
        }
        close();
        item.fire(); // after closing, so the callback may open a dialog or another menu
        if (item.isRadio()) say(item.getLabel() + ", selected", false);
        else if (item.isCheckable()) say(item.getLabel() + (item.isChecked() ? ", checked" : ", not checked"), false);
    }

    void close() {
        if (closed) return;
        if (win.getPopup() == this) {
            win.closePopup(); // its dismiss callback runs dispose()
        } else {
            dispose();
        }
    }

    void dispose() {
        if (closed) return;
        closed = true;
        font.close();
    }

    /* Input */

    @Override
    public boolean contains(double px, double py) {
        for (Panel panel : panels) {
            if (panel.contains(px, py)) return true;
        }
        return false;
    }

    @Override
    public void onPopupKey(int key, int mods) {
        if (closed) return;
        keyboardMode = true;
        Panel last = panels.get(panels.size() - 1);
        int depth = panels.size() - 1;
        int sizeBefore = panels.size(), activeBefore = last.active;

        switch (key) {
            case GLFW.GLFW_KEY_DOWN -> {
                last.active = step(last, last.active, 1);
                reveal(last, last.active);
            }
            case GLFW.GLFW_KEY_UP -> {
                last.active = step(last, last.active, -1);
                reveal(last, last.active);
            }
            case GLFW.GLFW_KEY_HOME -> {
                last.active = step(last, -1, 1);
                reveal(last, last.active);
            }
            case GLFW.GLFW_KEY_END -> {
                last.active = step(last, -1, -1);
                reveal(last, last.active);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                MenuItem item = last.active >= 0 ? last.items.get(last.active) : null;
                if (item != null && item.hasSubmenu() && item.isEnabled()) {
                    openChild(depth, last.active, true);
                } else if (depth == 0 && menu.sidewaysListener != null) {
                    menu.sidewaysListener.accept(1);
                }
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (depth > 0) {
                    truncate(depth);
                } else if (menu.sidewaysListener != null) {
                    menu.sidewaysListener.accept(-1);
                }
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (last.active >= 0) {
                    MenuItem item = last.items.get(last.active);
                    if (item.hasSubmenu() && selectable(item)) openChild(depth, last.active, true);
                    else activate(depth, last.active);
                }
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (depth > 0) truncate(depth);
                else close();
            }
            default -> {
                // A letter chooses the row it is the mnemonic of; Tab and everything else is swallowed.
                if ((mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) == 0) chooseByMnemonic(last, depth, key);
            }
        }

        if (closed) {
            if (key == GLFW.GLFW_KEY_ESCAPE && depth == 0) say("Menu closed", true);
            return;
        }
        Panel top = panels.get(panels.size() - 1);
        if (panels.size() != sizeBefore || top != last || top.active != activeBefore) sayRow(panels.size() - 1);
    }

    /**
     * The row after the active one (wrapping) whose mnemonic is the letter or digit {@code key}: chosen at once if it
     * is the only one, otherwise only highlighted, so a second press moves on to the next.
     */
    private void chooseByMnemonic(Panel panel, int depth, int key) {
        char typed;
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) typed = (char) ('a' + key - GLFW.GLFW_KEY_A);
        else if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) typed = (char) ('0' + key - GLFW.GLFW_KEY_0);
        else return;
        int n = panel.items.size(), first = -1, count = 0;
        for (int k = 1; k <= n; k++) {
            int i = Math.floorMod(panel.active + k, n);
            MenuItem item = panel.items.get(i);
            if (!selectable(item) || item.getMnemonic() == 0 || Character.toLowerCase(item.getMnemonic()) != typed) continue;
            if (first < 0) first = i;
            count++;
        }
        if (first < 0) return;
        panel.active = first;
        reveal(panel, first);
        MenuItem item = panel.items.get(first);
        if (count > 1) return;
        if (item.hasSubmenu()) openChild(depth, first, true);
        else activate(depth, first);
    }

    /**
     * Mouse-driven hover: highlights follow the pointer at once; opening/closing submenus is delayed.
     */
    @Override
    public void update(float delta) {
        super.update(delta);
        menu.advance(delta);
        if (opening != null && !closed) {
            String text = opening;
            opening = null;
            say(text, true);
        }
        if (closed || !MouseTracker.isInWindow()) return;

        double mx = MouseTracker.getX(), my = MouseTracker.getY();
        if (mx != lastMouseX || my != lastMouseY) {
            lastMouseX = mx;
            lastMouseY = my;
            keyboardMode = false;
        }
        if (keyboardMode) return;

        int hitPanel = -1, hitRow = -1;
        for (int p = panels.size() - 1; p >= 0 && hitPanel < 0; p--) {
            if (panels.get(p).contains(mx, my)) {
                hitPanel = p;
                hitRow = panels.get(p).rowAt(mx, my);
            }
        }
        if (hitPanel < 0 || hitRow < 0 || !selectable(panels.get(hitPanel).items.get(hitRow))) {
            pendingPanel = pendingRow = -1;
            return;
        }

        if (hitPanel != pendingPanel || hitRow != pendingRow) {
            pendingPanel = hitPanel;
            pendingRow = hitRow;
            pendingTime = 0f;
        } else {
            pendingTime += delta;
        }

        Panel panel = panels.get(hitPanel);
        MenuItem item = panel.items.get(hitRow);
        boolean deeperOpen = panels.size() > hitPanel + 1;
        if (deeperOpen && panel.active == hitRow) return; // back on the row that owns the open submenu

        if (deeperOpen) {
            if (pendingTime >= SWITCH_DELAY) openChild(hitPanel, hitRow, false);
        } else if (item.hasSubmenu()) {
            panel.active = hitRow;
            if (pendingTime >= OPEN_DELAY) openChild(hitPanel, hitRow, false);
        } else {
            panel.active = hitRow;
        }
        if (panel.active == hitRow && (spokenPanel != hitPanel || spokenRow != hitRow)) sayRow(hitPanel);
    }

    /* Speech */

    private me.piitex.engine.Window speechWindow() {
        return win instanceof me.piitex.engine.Window window ? window : getWindow();
    }

    private void say(String text, boolean urgent) {
        me.piitex.engine.Window window = speechWindow();
        if (window != null) window.announce(text, urgent);
    }

    /** "File menu, 6 items", or just "Menu" for a menu with no name. */
    private String openingText() {
        int count = 0;
        for (MenuItem item : menu.getItems()) if (!item.isSeparator()) count++;
        String name = menu.getAccessibleName();
        return (name == null || name.isBlank() ? "Menu" : name + " menu") + ", " + count + (count == 1 ? " item" : " items");
    }

    /**
     * Reads the highlighted row of the panel at depth {@code p}: "Save, Ctrl+S, 3 of 8", with its check state or "submenu"
     * where it has one. A menu's opening words go first if they have not been said yet.
     */
    private void sayRow(int p) {
        Panel panel = panels.get(p);
        spokenPanel = p;
        spokenRow = panel.active;
        if (panel.active < 0) return;
        MenuItem item = panel.items.get(panel.active);
        int position = 0, count = 0;
        for (int i = 0; i < panel.items.size(); i++) {
            if (panel.items.get(i).isSeparator()) continue;
            count++;
            if (i <= panel.active) position = count;
        }
        StringBuilder text = new StringBuilder(item.getLabel());
        if (item.isRadio()) text.append(item.isChecked() ? ", selected" : ", not selected");
        else if (item.isCheckable()) text.append(item.isChecked() ? ", checked" : ", not checked");
        if (item.hasSubmenu()) text.append(", submenu");
        String shortcut = item.getShortcut();
        if (shortcut != null && !shortcut.isEmpty()) text.append(", ").append(shortcut);
        text.append(", ").append(position).append(" of ").append(count);
        if (opening != null) {
            text.insert(0, opening + ". ");
            opening = null;
        }
        say(text.toString(), true);
    }

    /* Rendering */

    @Override
    public void render(Renderer renderer) {
        if (closed) return;
        boolean mouseOn = MouseTracker.isInWindow();
        float radius = Math.min(menu.theme().getCornerRadius(), 8f);
        float thickness = menu.theme().getBorderThickness();

        for (int p = 0; p < panels.size(); p++) {
            Panel panel = panels.get(p);
            renderer.drawQuad(panel.x + 1f, panel.y + 3f, panel.w, panel.h, new Color(0f, 0f, 0f, 0.20f), radius); // soft shadow
            renderer.drawQuad(panel.x, panel.y, panel.w, panel.h, menu.background(), radius);

            // Live hover only shows in the panel the pointer is over (and only while the mouse, not the
            // keyboard, is driving); other panels show the row that owns the next submenu.
            int hover = !keyboardMode && mouseOn ? panel.rowAt(MouseTracker.getX(), MouseTracker.getY()) : -1;
            int shown = hover >= 0 ? hover : panel.active;

            boolean scrolls = panel.contentH > panel.h;
            if (scrolls) renderer.pushClip(panel.x, panel.y, panel.w, panel.h);
            for (int i = 0; i < panel.items.size(); i++) {
                MenuItem item = panel.items.get(i);
                float rowY = panel.y + panel.rowTop[i] - panel.scroll, rowH = panel.rowH[i];
                if (rowY + rowH < panel.y || rowY > panel.y + panel.h) continue; // scrolled out of sight
                if (item.isSeparator()) {
                    float ly = rowY + rowH / 2f;
                    renderer.drawLine(panel.x + PAD_X + 4f, ly, panel.x + panel.w - PAD_X - 4f, ly, menu.border(), 1f);
                    continue;
                }
                boolean enabled = item.isEnabled();
                if (i == shown && enabled) {
                    renderer.drawQuad(panel.x + PAD_X, rowY, panel.w - 2 * PAD_X, rowH, menu.highlight(), Math.min(radius, 6f));
                }
                Paint color = enabled ? menu.text() : menu.disabledText();
                float textX = panel.x + PAD_X + TEXT_PAD;

                if (item.isRadio() && item.isChecked()) {
                    renderer.drawCircle(textX + 5f, rowY + rowH / 2f, 3.2f, color);
                } else if (item.isCheckable() && item.isChecked()) {
                    float cx = textX + 6f, cy = rowY + rowH / 2f;
                    renderer.drawLine(cx - 4f, cy, cx - 1f, cy + 3.5f, color, 2f);
                    renderer.drawLine(cx - 1f, cy + 3.5f, cx + 5f, cy - 4f, color, 2f);
                }
                if (panel.checkCol) textX += CHECK_COL;

                if (item.getIcon() != null) {
                    int size = Math.round(ICON_SIZE);
                    renderer.drawImage(IconCache.loadResource(item.getIcon().resourcePath(), size, size), textX,
                            rowY + (rowH - size) / 2f, size, size, 0f, 1f, solid(color));
                }
                if (panel.iconCol) textX += ICON_COL;

                float textH = FontMetricsCompat.of(font).getHeight();
                float textY = rowY + (rowH - textH) / 2f;
                renderer.drawText(item.getLabel(), textX, textY, font, color);
                int mnemonic = item.mnemonicIndex();
                if (mnemonic >= 0) {
                    float ux = textX + TextShaping.width(font, item.getLabel().substring(0, mnemonic));
                    float uw = TextShaping.width(font, item.getLabel().substring(mnemonic, mnemonic + 1));
                    renderer.drawLine(ux, textY + textH + 1f, ux + uw, textY + textH + 1f, color, 1f);
                }

                String shortcut = item.getShortcut();
                if (shortcut != null && !shortcut.isEmpty()) {
                    float sx = panel.x + panel.w - PAD_X - TEXT_PAD - (panel.arrowCol ? ARROW_COL : 0f)
                            - TextShaping.width(font, shortcut);
                    renderer.drawText(shortcut, sx, rowY + (rowH - textH) / 2f, font, menu.disabledText());
                }
                if (item.hasSubmenu()) {
                    float ax = panel.x + panel.w - PAD_X - TEXT_PAD - 2f, ay = rowY + rowH / 2f;
                    renderer.drawLine(ax - 4f, ay - 4f, ax, ay, color, 1.6f);
                    renderer.drawLine(ax, ay, ax - 4f, ay + 4f, color, 1.6f);
                }
            }

            if (scrolls) {
                renderer.popClip();
                float cx = panel.x + panel.w / 2f;
                Paint hint = menu.disabledText();
                if (panel.scroll > 0) { // more above
                    renderer.drawLine(cx - 4f, panel.y + 8f, cx, panel.y + 4f, hint, 1.6f);
                    renderer.drawLine(cx, panel.y + 4f, cx + 4f, panel.y + 8f, hint, 1.6f);
                }
                if (panel.scroll < panel.contentH - panel.h) { // more below
                    float by = panel.y + panel.h;
                    renderer.drawLine(cx - 4f, by - 8f, cx, by - 4f, hint, 1.6f);
                    renderer.drawLine(cx, by - 4f, cx + 4f, by - 8f, hint, 1.6f);
                }
            }

            if (thickness > 0) {
                renderer.drawBorder(panel.x, panel.y, panel.w, panel.h, menu.border(), thickness, radius);
            }
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
