package me.piitex.engine.ui.text;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Typeface;

/**
 * Drives {@link Element#getTooltip()}: finds the deepest hovered Element that has a
 * tooltip, times how long the cursor has rested on it, and draws the bubble above
 * every Container once that passes {@link Element#getTooltipDelay()}. Static, like
 * {@link me.piitex.engine.input.MouseTracker}, and only ever touched from the render
 * thread. {@link me.piitex.engine.Engine} calls {@link #update} and then
 * {@link #render} once per frame, the latter last so nothing draws over it.
 */
public final class TooltipManager {
    private static final float FONT_SIZE = 13f;
    private static final float PADDING = 6f;
    private static final float CURSOR_OFFSET_X = 12f;
    private static final float CURSOR_OFFSET_Y = 20f;

    private static Element target;
    private static float hoverTime;
    private static org.jetbrains.skia.Font font;
    private static Typeface fontTypeface;

    private TooltipManager() {}

    /** Advances the hover timer; restarts it whenever the tooltip-bearing Element under the cursor changes. */
    public static void update(Window window, float delta) {
        Element found = MouseTracker.isInWindow() ? findTarget(window) : null;
        if (found != target) {
            target = found;
            hoverTime = 0f;
        } else if (target != null) {
            hoverTime += delta;
        }
    }

    /** Draws the current tooltip, if its delay has elapsed. */
    public static void render(Renderer renderer, Window window) {
        if (target == null || hoverTime < target.getTooltipDelay()) return;
        String text = target.getTooltip();
        if (text == null || text.isEmpty()) return;

        org.jetbrains.skia.Font f = getFont();
        float lineHeight = f.getMetrics().getHeight();
        String[] lines = text.split("\n", -1);
        float textWidth = 0f;
        for (String line : lines) {
            textWidth = Math.max(textWidth, f.measureTextWidth(line));
        }
        float w = textWidth + PADDING * 2;
        float h = lineHeight * lines.length + PADDING * 2;

        float x = (float) MouseTracker.getX() + CURSOR_OFFSET_X;
        float y = (float) MouseTracker.getY() + CURSOR_OFFSET_Y;
        // Keep it on screen: flip above/left of the cursor rather than clipping.
        float winW = window.getWindowOptions().getWidth();
        float winH = window.getWindowOptions().getHeight();
        if (x + w > winW) x = Math.max(0f, (float) MouseTracker.getX() - CURSOR_OFFSET_X - w);
        if (y + h > winH) y = Math.max(0f, (float) MouseTracker.getY() - h - 6f);

        Theme theme = ThemeManager.getCurrent();
        renderer.drawQuad(x, y, w, h, theme.getTooltipBackground(), 4f);
        renderer.drawBorder(x, y, w, h, theme.getTooltipBorder(), 1f, 4f);
        for (int i = 0; i < lines.length; i++) {
            renderer.drawText(lines[i], x + PADDING, y + PADDING + i * lineHeight, f, theme.getTooltipText());
        }
    }

    private static org.jetbrains.skia.Font getFont() {
        Typeface typeface = Font.getDefault();
        if (font == null || typeface != fontTypeface) {
            if (font != null) font.close();
            font = new org.jetbrains.skia.Font(typeface, FONT_SIZE);
            fontTypeface = typeface;
        }
        return font;
    }

    /** The deepest hovered Element with a tooltip, in the topmost Container that has one. */
    private static Element findTarget(Window window) {
        Element best = null;
        for (Container container : window.getContainers().values()) {
            Element found = findIn(container);
            if (found != null) best = found;
        }
        return best;
    }

    private static Element findIn(Container container) {
        if (!container.isEnabled() || !container.isHovered()) return null;
        Element best = hasTooltip(container) ? container : null;
        for (Element child : container.getElements().values()) {
            if (!child.isEnabled() || !child.isHovered()) continue;
            Element found = child instanceof Container c ? findIn(c) : (hasTooltip(child) ? child : null);
            if (found != null) best = found;
        }
        return best;
    }

    private static boolean hasTooltip(Element e) {
        return e.getTooltip() != null && !e.getTooltip().isEmpty();
    }
}
