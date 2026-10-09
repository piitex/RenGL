package me.piitex.engine.ui.text;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Typeface;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.ToDoubleFunction;

/**
 * Drives {@link Element#getTooltip()}: finds the deepest hovered Element that has a
 * tooltip, times how long the cursor has rested on it, and draws the bubble above
 * every Container once that passes {@link Element#getTooltipDelay()}. Static, like
 * {@link me.piitex.engine.input.MouseTracker}, and only ever touched from the render
 * thread. {@link me.piitex.engine.Engine} calls {@link #update} and then
 * {@link #render} once per frame, the latter last so nothing draws over it.
 * <p>
 * A line of the tooltip's text that is wider than about 360 points, or than the window, is wrapped at spaces; a line
 * break in the text still starts a new line.
 */
public final class TooltipManager {
    private static final float FONT_SIZE = 13f;
    private static final float PADDING = 6f;
    private static final float CURSOR_OFFSET_X = 12f;
    private static final float CURSOR_OFFSET_Y = 20f;
    private static final float MAX_WIDTH = 360f; // a longer line is wrapped

    // Each window has its own tooltip. Render thread only.
    private static final Map<Window, Hover> hovers = new WeakHashMap<>();
    private static org.jetbrains.skia.Font font;
    private static Typeface fontTypeface;

    private TooltipManager() {
    }

    /**
     * Advances the hover timer; restarts it whenever the tooltip-bearing Element under the cursor changes.
     */
    public static void update(Window window, float delta) {
        Hover hover = hovers.computeIfAbsent(window, w -> new Hover());
        Element found = MouseTracker.isInWindow() ? findTarget(window) : null;
        if (found != hover.target) {
            hover.target = found;
            hover.time = 0f;
        } else if (hover.target != null) {
            hover.time += delta;
            if (hover.time >= hover.target.getTooltipDelay()) {
                Theme theme = hover.target.getTooltipTheme() != null ? hover.target.getTooltipTheme() : ThemeManager.getCurrent();
                AnimatedPaint.advanceIfAnimated(theme.getTooltipBackground(), delta);
                AnimatedPaint.advanceIfAnimated(theme.getTooltipBorder(), delta);
                AnimatedPaint.advanceIfAnimated(theme.getTooltipText(), delta);
            }
        }
    }

    /**
     * Drops a closed window's tooltip state.
     */
    public static void forget(Window window) {
        hovers.remove(window);
    }

    private static final class Hover {
        Element target;
        float time;
    }

    /**
     * Draws the current tooltip, if its delay has elapsed.
     */
    public static void render(Renderer renderer, Window window) {
        Hover hover = hovers.get(window);
        if (hover == null) return;
        Element target = hover.target;
        if (target == null || hover.time < target.getTooltipDelay()) return;
        String text = target.getTooltip();
        if (text == null || text.isEmpty()) return;

        org.jetbrains.skia.Font f = getFont();
        float lineHeight = FontMetricsCompat.of(f).getHeight();
        float winW = window.getWindowOptions().getWidth();
        float winH = window.getWindowOptions().getHeight();
        List<String> lines = wrap(text, Math.max(80f, Math.min(MAX_WIDTH, winW - 2 * PADDING - 8f)), f::measureTextWidth);
        float textWidth = 0f;
        for (String line : lines) {
            textWidth = Math.max(textWidth, f.measureTextWidth(line));
        }
        float w = textWidth + PADDING * 2;
        float h = lineHeight * lines.size() + PADDING * 2;

        float x = (float) MouseTracker.getX() + CURSOR_OFFSET_X;
        float y = (float) MouseTracker.getY() + CURSOR_OFFSET_Y;
        // Keep it on screen: flip above/left of the cursor rather than clipping.
        if (x + w > winW) x = Math.max(0f, (float) MouseTracker.getX() - CURSOR_OFFSET_X - w);
        if (y + h > winH) y = Math.max(0f, (float) MouseTracker.getY() - h - 6f);

        Theme theme = target.getTooltipTheme() != null ? target.getTooltipTheme() : ThemeManager.getCurrent();
        renderer.drawQuad(x, y, w, h, theme.getTooltipBackground(), 4f);
        renderer.drawBorder(x, y, w, h, theme.getTooltipBorder(), 1f, 4f);
        for (int i = 0; i < lines.size(); i++) {
            renderer.drawText(lines.get(i), x + PADDING, y + PADDING + i * lineHeight, f, theme.getTooltipText());
        }
    }

    /**
     * Breaks {@code text} into lines no wider than {@code maxWidth} by {@code measure}, at spaces. A line break in the
     * text is kept, and a single word wider than the limit is left on a line of its own rather than cut.
     */
    static List<String> wrap(String text, float maxWidth, ToDoubleFunction<String> measure) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (measure.applyAsDouble(paragraph) <= maxWidth) {
                lines.add(paragraph);
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (!line.isEmpty() && measure.applyAsDouble(line + " " + word) > maxWidth) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
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

    /**
     * The deepest hovered Element with a tooltip, in the topmost Container that has one.
     */
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
