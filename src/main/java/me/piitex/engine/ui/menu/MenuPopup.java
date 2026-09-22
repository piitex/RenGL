package me.piitex.engine.ui.menu;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.PopupKeyListener;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
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
final class MenuPopup extends Container implements PopupKeyListener {
    private static final float PAD_Y = 4f;         // panel top/bottom padding
    private static final float PAD_X = 4f;         // gap between panel edge and highlight
    private static final float TEXT_PAD = 10f;     // inside the highlight, before the label
    private static final float CHECK_COL = 20f;
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
        boolean checkCol, shortcutCol, arrowCol;
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
            double local = py - y;
            for (int i = 0; i < rowTop.length; i++) {
                if (local >= rowTop[i] && local < rowTop[i] + rowH[i]) return i;
            }
            return -1;
        }
    }

    private final PopupMenu menu;
    private final Window win;
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

    MenuPopup(PopupMenu menu, Window win, double x, double y) {
        super(0, 0);
        this.menu = menu;
        this.win = win;
        this.font = new org.jetbrains.skia.Font(Font.getDefault(), menu.getFontSize());
        setCursorMode(CursorMode.POINTER);

        panels.add(layout(menu.getItems(), x, y));
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
            panel.labelW = Math.max(panel.labelW, font.measureTextWidth(item.getLabel()));
            if (item.isCheckable()) panel.checkCol = true;
            if (item.hasSubmenu()) panel.arrowCol = true;
            if (item.getShortcut() != null && !item.getShortcut().isEmpty()) {
                panel.shortcutCol = true;
                panel.shortcutW = Math.max(panel.shortcutW, font.measureTextWidth(item.getShortcut()));
            }
        }
        panel.h = top + PAD_Y;
        panel.w = Math.max(menu.getMinWidth(), 2 * PAD_X + 2 * TEXT_PAD
                + (panel.checkCol ? CHECK_COL : 0f)
                + panel.labelW
                + (panel.shortcutCol ? SHORTCUT_GAP + panel.shortcutW : 0f)
                + (panel.arrowCol ? ARROW_COL : 0f));

        double winW = win.getWindowOptions().getWidth();
        double winH = win.getWindowOptions().getHeight();
        panel.x = (float) Math.max(0, Math.min(x, winW - panel.w));
        panel.y = (float) Math.max(0, Math.min(y, winH - panel.h));
        return panel;
    }

    /**
     * Opens the submenu of {@code row} in panel {@code p} beside it, closing anything deeper.
     */
    private void openChild(int p, int row, boolean selectFirst) {
        truncate(p + 1);
        Panel parent = panels.get(p);
        parent.active = row;
        MenuItem item = parent.items.get(row);
        if (!item.hasSubmenu()) return;

        double winW = win.getWindowOptions().getWidth();
        double y = parent.y + parent.rowTop[row] - PAD_Y;
        Panel child = layout(item.getChildren(), parent.x + parent.w - 2, y);
        if (parent.x + parent.w - 2 + child.w > winW) {
            child.x = Math.max(0, parent.x - child.w + 2); // no room on the right, so flip to the left
        }
        if (selectFirst) child.active = step(child, -1, 1);
        panels.add(child);
    }

    private void truncate(int size) {
        while (panels.size() > size) panels.remove(panels.size() - 1);
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

    private void activate(int p, int row) {
        MenuItem item = panels.get(p).items.get(row);
        if (!selectable(item)) return;
        if (item.hasSubmenu()) {
            openChild(p, row, false);
            return;
        }
        close();
        item.fire(); // after closing, so the callback may open a dialog or another menu
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

        switch (key) {
            case GLFW.GLFW_KEY_DOWN -> last.active = step(last, last.active, 1);
            case GLFW.GLFW_KEY_UP -> last.active = step(last, last.active, -1);
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
            default -> { /* Tab and everything else is swallowed while a menu is open */ }
        }
    }

    /**
     * Mouse-driven hover: highlights follow the pointer at once; opening/closing submenus is delayed.
     */
    @Override
    public void update(float delta) {
        super.update(delta);
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

            for (int i = 0; i < panel.items.size(); i++) {
                MenuItem item = panel.items.get(i);
                float rowY = panel.y + panel.rowTop[i], rowH = panel.rowH[i];
                if (item.isSeparator()) {
                    float ly = rowY + rowH / 2f;
                    renderer.drawLine(panel.x + PAD_X + 4f, ly, panel.x + panel.w - PAD_X - 4f, ly, menu.border(), 1f);
                    continue;
                }
                boolean enabled = item.isEnabled();
                if (i == shown && enabled) {
                    renderer.drawQuad(panel.x + PAD_X, rowY, panel.w - 2 * PAD_X, rowH, menu.highlight(), Math.min(radius, 6f));
                }
                Color color = enabled ? menu.text() : menu.disabledText();
                float textX = panel.x + PAD_X + TEXT_PAD;

                if (item.isCheckable() && item.isChecked()) {
                    float cx = textX + 6f, cy = rowY + rowH / 2f;
                    renderer.drawLine(cx - 4f, cy, cx - 1f, cy + 3.5f, color, 2f);
                    renderer.drawLine(cx - 1f, cy + 3.5f, cx + 5f, cy - 4f, color, 2f);
                }
                if (panel.checkCol) textX += CHECK_COL;

                float textH = font.getMetrics().getHeight();
                renderer.drawText(item.getLabel(), textX, rowY + (rowH - textH) / 2f, font, color);

                String shortcut = item.getShortcut();
                if (shortcut != null && !shortcut.isEmpty()) {
                    float sx = panel.x + panel.w - PAD_X - TEXT_PAD - (panel.arrowCol ? ARROW_COL : 0f)
                            - font.measureTextWidth(shortcut);
                    renderer.drawText(shortcut, sx, rowY + (rowH - textH) / 2f, font, menu.disabledText());
                }
                if (item.hasSubmenu()) {
                    float ax = panel.x + panel.w - PAD_X - TEXT_PAD - 2f, ay = rowY + rowH / 2f;
                    renderer.drawLine(ax - 4f, ay - 4f, ax, ay, color, 1.6f);
                    renderer.drawLine(ax, ay, ax - 4f, ay + 4f, color, 1.6f);
                }
            }

            if (thickness > 0) {
                renderer.drawBorder(panel.x, panel.y, panel.w, panel.h, menu.border(), thickness, radius);
            }
        }
    }
}
