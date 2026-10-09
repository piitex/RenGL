package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.scroll.ScrollBarStyle;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.text.TextLayout;
import me.piitex.engine.ui.theme.Theme;
import org.jetbrains.skia.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only text that flows within a fixed-width box: word-wrapped, optionally aligned,
 * and sized to fit: a label for paragraphs. This is the display-only counterpart of
 * {@link TextAreaOverlay}, with the same greedy word wrap and line spacing but no caret,
 * selection, focus, or scrolling.
 * <p>
 * Extends {@link TextOverlay}, so font, size, text color and theming all behave exactly
 * as they do there (including "{@link #setTextColor} pins against theme switches").
 * Width is what you construct it with; height follows the wrapped text by default (see
 * {@link #setAutoHeight}), which lets a {@link me.piitex.engine.ui.layout.Layout} space
 * it correctly. With auto-height off, the box keeps a fixed height. {@link #setMaxLines}
 * instead cuts the text with an ellipsis, so nothing is left to scroll.
 * <p>
 * Set {@link #setMaxHeight} to cap the growth: once the wrapped text is taller than
 * that, the box stops growing and gains a scrollbar (mouse wheel via {@link Scrollable},
 * plus a draggable thumb) styled by {@link #getScrollBarStyle()}. The same scrolling
 * kicks in for a fixed-height box ({@link #setAutoHeight}{@code (false)}) whose text
 * overflows.
 * <p>
 * {@code '\n'} in the text forces a line break. A single word wider than the box is
 * broken mid-word rather than overflowing.
 */
public class TextFlowOverlay extends TextOverlay implements Scrollable, Draggable {
    private static final String ELLIPSIS = "…";

    private final List<String> lines = new ArrayList<>();
    private boolean ready; // TextOverlay's constructor calls updateComputedSize() before our fields exist
    private TextAlign textAlign = TextAlign.LEFT;
    private float lineSpacing = 2f;
    private float padding = 0f;
    private int maxLines = 0; // 0 = unlimited
    private boolean autoHeight = true;
    private float maxHeight = 0f; // 0 = no cap

    private float scrollOffsetY = 0f;
    private float scrollSpeed = 40f;
    private boolean reserveBar; // true while the scrollbar's width is carved out of the wrap width
    private final ScrollBarStyle scrollBarStyle = new ScrollBarStyle();
    private boolean scrollbarVisible = true;
    private boolean draggingThumb;
    private double thumbDragAnchorMouseY, thumbDragAnchorOffsetY;
    private static final double DRAG_THRESHOLD_PIXELS = 4.0;

    public TextFlowOverlay(String text, double width) {
        this(text, width, 16f, me.piitex.engine.ui.color.Color.BLACK);
    }

    public TextFlowOverlay(String text, double width, float fontSize, Paint textColor) {
        super(text, fontSize, textColor);
        setWidth(width);
        ready = true;
        applyTheme(me.piitex.engine.ui.theme.ThemeManager.getCurrent());
        updateComputedSize();
    }

    public TextFlowOverlay(String text, double width, File fontFile, float fontSize, Paint textColor) {
        super(text, fontFile, fontSize, textColor);
        setWidth(width);
        ready = true;
        applyTheme(me.piitex.engine.ui.theme.ThemeManager.getCurrent());
        updateComputedSize();
    }

    public TextAlign getTextAlign() {
        return textAlign;
    }

    public void setTextAlign(TextAlign textAlign) {
        this.textAlign = textAlign == null ? TextAlign.LEFT : textAlign;
    }

    public float getLineSpacing() {
        return lineSpacing;
    }

    /**
     * Extra vertical gap between lines, in pixels, on top of the font's own line height.
     */
    public void setLineSpacing(float lineSpacing) {
        this.lineSpacing = Math.max(0f, lineSpacing);
        updateComputedSize();
    }

    public float getPadding() {
        return padding;
    }

    /**
     * Inset between the box's edge and the text, on all four sides.
     */
    public void setPadding(float padding) {
        this.padding = Math.max(0f, padding);
        updateComputedSize();
    }

    public int getMaxLines() {
        return maxLines;
    }

    /**
     * Caps the number of lines shown; the last one ends in an ellipsis if text was cut. 0 (default) means no cap.
     */
    public void setMaxLines(int maxLines) {
        this.maxLines = Math.max(0, maxLines);
        updateComputedSize();
    }

    public boolean isAutoHeight() {
        return autoHeight;
    }

    /**
     * When true (default) the height tracks the wrapped text; when false it stays whatever {@link #setHeight} last set and overflow is clipped.
     */
    public void setAutoHeight(boolean autoHeight) {
        this.autoHeight = autoHeight;
        updateComputedSize();
    }

    public float getMaxHeight() {
        return maxHeight;
    }

    /**
     * With auto-height on, caps how tall the box grows; past it the text scrolls. 0 (default) means no cap.
     */
    public void setMaxHeight(float maxHeight) {
        this.maxHeight = Math.max(0f, maxHeight);
        updateComputedSize();
    }

    public float getScrollSpeed() {
        return scrollSpeed;
    }

    /**
     * Logical points scrolled per unit of raw wheel offset. Larger is faster.
     */
    public void setScrollSpeed(float scrollSpeed) {
        this.scrollSpeed = scrollSpeed;
    }

    public boolean isScrollbarVisible() {
        return scrollbarVisible;
    }

    /**
     * Whether the thumb is drawn/draggable. Wheel scrolling still works when false.
     */
    public void setScrollbarVisible(boolean scrollbarVisible) {
        this.scrollbarVisible = scrollbarVisible;
    }

    /**
     * Thumb and track colors and geometry, using the same style object {@link me.piitex.engine.ui.scroll.ScrollContainer} uses.
     */
    public ScrollBarStyle getScrollBarStyle() {
        return scrollBarStyle;
    }

    public double getMaxScrollY() {
        return Math.max(0, contentHeight() - getHeight());
    }

    public void scrollBy(double dy) {
        scrollTo(scrollOffsetY + dy);
    }

    public void scrollTo(double y) {
        scrollOffsetY = (float) Math.max(0, Math.min(y, getMaxScrollY()));
    }

    @Override
    public boolean canScroll(double deltaX, double deltaY) {
        if (deltaY == 0) return false;
        return deltaY > 0 ? scrollOffsetY > 0 : scrollOffsetY < getMaxScrollY();
    }

    @Override
    public void onScroll(double deltaX, double deltaY) {
        scrollBy(-deltaY * scrollSpeed);
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        if (ready) scrollBarStyle.advance(delta);
    }

    @Override
    public void applyTheme(Theme theme) {
        super.applyTheme(theme);
        if (!ready) return; // TextOverlay's constructor registers us before our own fields exist
        scrollBarStyle.applyThemeTrackColor(theme.getScrollTrack());
        scrollBarStyle.applyThemeThumbColor(theme.getScrollThumb());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        scrollBarStyle.followTheme();
    }

    /**
     * A fixed-height box's height is the caller's; re-wrap since the scrollbar may now be needed.
     */
    @Override
    public void setHeight(double height) {
        super.setHeight(height);
        if (ready && !autoHeight) updateComputedSize();
    }

    /**
     * Changing the width re-wraps the text.
     */
    @Override
    public void setWidth(double width) {
        super.setWidth(width);
        if (ready) updateComputedSize();
    }

    /**
     * Re-wraps to the current width/font/text, and (if auto-height) resizes the box to match.
     */
    @Override
    protected void updateComputedSize() {
        if (!ready) return;
        reserveBar = false;
        layoutLines();
        float viewport = autoHeight ? (maxHeight > 0 ? maxHeight : Float.MAX_VALUE) : (float) getHeight();
        if (scrollbarVisible && contentHeight() > viewport) {
            reserveBar = true; // scrollbar needs room, so wrap narrower and re-measure
            layoutLines();
        }
        if (autoHeight) {
            float h = contentHeight();
            super.setHeight(maxHeight > 0 ? Math.min(h, maxHeight) : h);
        }
        scrollTo(scrollOffsetY); // re-clamp
    }

    private float contentHeight() {
        int n = lines.size();
        return n == 0 ? 2 * padding : 2 * padding + n * linePitch() - lineSpacing;
    }

    private float barWidth() {
        return scrollBarStyle.getThickness() + 2 * scrollBarStyle.getMargin();
    }

    private float linePitch() {
        FontMetricsCompat m = FontMetricsCompat.of(getOrBuildFont());
        return -m.getAscent() + m.getDescent() + lineSpacing;
    }

    private void layoutLines() {
        lines.clear();
        String text = getText();
        if (text == null || text.isEmpty()) return;

        Font font = getOrBuildFont();
        float innerWidth = Math.max(1f, (float) getWidth() - 2 * padding - (reserveBar ? barWidth() : 0f));

        lines.addAll(TextLayout.wrap(text, font, innerWidth));

        if (maxLines > 0 && lines.size() > maxLines) {
            while (lines.size() > maxLines) lines.remove(lines.size() - 1);
            String last = lines.get(maxLines - 1);
            while (!last.isEmpty() && font.measureTextWidth(last + ELLIPSIS) > innerWidth) {
                last = last.substring(0, last.length() - 1);
            }
            lines.set(maxLines - 1, last.stripTrailing() + ELLIPSIS);
        }
    }

    @Override
    protected void drawText(Renderer renderer) {
        if (lines.isEmpty()) return;

        Font font = getOrBuildFont();
        float pitch = linePitch();
        float innerWidth = (float) getWidth() - 2 * padding - (reserveBar ? barWidth() : 0f);
        boolean scrollable = getMaxScrollY() > 0;

        renderer.pushClip((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            float y = (float) getY() + padding + i * pitch - scrollOffsetY;
            if (line.isEmpty() || y + pitch < getY() || y > getY() + getHeight()) continue;
            float x = (float) getX() + padding;
            float slack = innerWidth - font.measureTextWidth(line);
            float natural = textAlign.fraction();
            x += slack * textAlignH(natural);
            renderer.drawText(line, x, y, font, resolveTextColor(getTextColor()));
        }
        renderer.popClip();

        if (scrollable && scrollbarVisible) drawScrollbar(renderer);
    }

    private void drawScrollbar(Renderer renderer) {
        Paint track = scrollBarStyle.getTrackColor();
        if (track != null) {
            renderer.drawQuad((float) trackX(), (float) trackY(), scrollBarStyle.getThickness(), (float) trackHeight(), track, scrollBarStyle.getCornerRadius());
        }
        Paint thumb = draggingThumb ? scrollBarStyle.getThumbActiveColor() : scrollBarStyle.getThumbColor();
        if (thumb != null) {
            renderer.drawQuad((float) trackX(), (float) thumbY(), scrollBarStyle.getThickness(), (float) thumbLength(), thumb, scrollBarStyle.getCornerRadius());
        }
    }

    // --- Scrollbar geometry & thumb dragging (same math as TextAreaOverlay) ----------------------

    private double trackX() {
        return getX() + getWidth() - scrollBarStyle.getThickness() - scrollBarStyle.getMargin();
    }

    private double trackY() {
        return getY() + scrollBarStyle.getMargin();
    }

    private double trackHeight() {
        return Math.max(0, getHeight() - 2 * scrollBarStyle.getMargin());
    }

    private double thumbLength() {
        double trackH = trackHeight();
        double contentH = getMaxScrollY() + getHeight();
        if (contentH <= 0) return trackH;
        double raw = trackH * (getHeight() / contentH);
        return Math.max(scrollBarStyle.getMinThumbLength(), Math.min(trackH, raw));
    }

    private double thumbY() {
        double maxScroll = getMaxScrollY();
        double travel = trackHeight() - thumbLength();
        if (maxScroll <= 0 || travel <= 0) return trackY();
        return trackY() + travel * (scrollOffsetY / maxScroll);
    }

    private boolean thumbContains(double px, double py) {
        if (!scrollbarVisible || getMaxScrollY() <= 0) return false;
        double x = trackX(), y = thumbY(), w = scrollBarStyle.getThickness(), h = thumbLength();
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    @Override
    public void onDragStart(double x, double y) {
        if (thumbContains(toLocalX(x), toLocalY(y))) {
            draggingThumb = true;
            thumbDragAnchorMouseY = y;
            thumbDragAnchorOffsetY = scrollOffsetY;
        }
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (!draggingThumb) return;
        if (Math.abs(y - thumbDragAnchorMouseY) < DRAG_THRESHOLD_PIXELS) return; // ignore click jitter
        double travel = trackHeight() - thumbLength();
        double ratio = travel > 0 ? (y - thumbDragAnchorMouseY) / travel : 0;
        scrollTo(thumbDragAnchorOffsetY + ratio * getMaxScrollY());
    }

    @Override
    public void onDragEnd(double x, double y) {
        draggingThumb = false;
    }
}
