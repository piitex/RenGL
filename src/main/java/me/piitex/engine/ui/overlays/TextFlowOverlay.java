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

import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.text.InlineRun;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.Browser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
 * <p>
 * {@link #setRuns} styles stretches of the text as bold, italic, code or a link, which wrap and align together with
 * the plain text. A link opens through {@link #setLinkHandler}, by default in the system browser.
 */
public class TextFlowOverlay extends TextOverlay implements Scrollable, Draggable {
    private static final String ELLIPSIS = "…";

    private static final float ITALIC_SKEW = -0.25f;
    private static final int BOLD = 1, ITALIC = 2, CODE = 4;

    private final List<String> lines = new ArrayList<>();
    private final List<Integer> lineStarts = new ArrayList<>(); // with runs: where each line begins in the text
    private final List<Integer> lineSources = new ArrayList<>(); // with runs: how many of a line's characters are the text's own
    private List<InlineRun> runs = List.of();
    private byte[] flags = new byte[0];  // per character of the text: BOLD, ITALIC and CODE bits
    private int[] linkOf = new int[0];   // per character: 1 + the index into hrefs, or 0 for none
    private List<String> hrefs = List.of();
    private final org.jetbrains.skia.Font[] runFonts = new org.jetbrains.skia.Font[8];
    private org.jetbrains.skia.Font runBase;
    private Consumer<String> linkHandler = Browser::open;
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

    /**
     * Sets the text and drops its {@link #setRuns runs}, which index into the old string.
     */
    @Override
    public void setText(String text) {
        if (ready && !runs.isEmpty()) clearRuns();
        super.setText(text);
    }

    /**
     * Styles stretches of the current text: each {@link InlineRun} is bold, italic, code, a link, or a mix. Indices
     * past the end of the text are clipped. Call it after the text is set; setting the text again drops the runs. An
     * empty list or null goes back to plain text.
     */
    public void setRuns(List<InlineRun> runs) {
        String text = getText() == null ? "" : getText();
        this.runs = runs == null ? List.of() : List.copyOf(runs);
        flags = new byte[text.length()];
        linkOf = new int[text.length()];
        List<String> links = new ArrayList<>();
        for (InlineRun run : this.runs) {
            int from = Math.min(run.start(), text.length()), to = Math.min(run.end(), text.length());
            int link = 0;
            if (run.href() != null) {
                links.add(run.href());
                link = links.size();
            }
            int bits = (run.bold() ? BOLD : 0) | (run.italic() ? ITALIC : 0) | (run.code() ? CODE : 0);
            for (int i = from; i < to; i++) {
                flags[i] |= (byte) bits;
                if (link != 0) linkOf[i] = link;
            }
        }
        hrefs = links;
        setBuiltInClickListener(links.isEmpty() ? null : event -> {
            String href = hrefAt(toLocalX(event.getMouseX()), toLocalY(event.getMouseY()));
            if (href != null && linkHandler != null) linkHandler.accept(href);
        });
        updateComputedSize();
    }

    private void clearRuns() {
        runs = List.of();
        flags = new byte[0];
        linkOf = new int[0];
        hrefs = List.of();
        setBuiltInClickListener(null);
    }

    public List<InlineRun> getRuns() {
        return runs;
    }

    /**
     * What a click on a link run does with its address. The default opens it through {@link Browser#open(String)},
     * which only accepts web and mail addresses. Null makes links do nothing.
     */
    public void setLinkHandler(Consumer<String> handler) {
        this.linkHandler = handler;
    }

    @Override
    public CursorMode getCursorMode() {
        if (!isEffectivelyDisabled() && !hrefs.isEmpty() && hrefAt(toLocalX(MouseTracker.getX()), toLocalY(MouseTracker.getY())) != null) {
            return CursorMode.POINTER;
        }
        return super.getCursorMode();
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
        lineStarts.clear();
        lineSources.clear();
        String text = getText();
        if (text == null || text.isEmpty()) return;

        Font font = getOrBuildFont();
        float innerWidth = Math.max(1f, (float) getWidth() - 2 * padding - (reserveBar ? barWidth() : 0f));

        boolean styled = flags.length == text.length() && hasStyle();
        if (styled) wrapStyled(text, innerWidth);
        else lines.addAll(TextLayout.wrap(text, font, innerWidth));

        if (maxLines > 0 && lines.size() > maxLines) {
            while (lines.size() > maxLines) lines.remove(lines.size() - 1);
            String last = lines.get(maxLines - 1);
            while (!last.isEmpty() && font.measureTextWidth(last + ELLIPSIS) > innerWidth) {
                last = last.substring(0, last.length() - 1);
            }
            lines.set(maxLines - 1, last.stripTrailing() + ELLIPSIS);
            if (styled) lineSources.set(maxLines - 1, last.stripTrailing().length());
        }
    }

    private boolean hasStyle() {
        for (byte flag : flags) if (flag != 0) return true;
        for (int link : linkOf) if (link != 0) return true;
        return false;
    }

    /**
     * Wraps like {@link TextLayout#wrap}, but measures each stretch in the font its run draws with, and remembers
     * where in the text every line starts so the runs can be found again when drawing.
     */
    private void wrapStyled(String text, float maxWidth) {
        int paragraph = 0;
        while (paragraph <= text.length()) {
            int end = text.indexOf('\n', paragraph);
            if (end < 0) end = text.length();
            wrapParagraphStyled(text, paragraph, end, maxWidth);
            paragraph = end + 1;
        }
    }

    private void wrapParagraphStyled(String text, int from, int to, float maxWidth) {
        if (from == to) {
            addLine(text, from, to);
            return;
        }
        int lineStart = from, lineEnd = -1, wordStart = from;
        while (wordStart <= to) {
            int wordEnd = text.indexOf(' ', wordStart);
            if (wordEnd < 0 || wordEnd > to) wordEnd = to;
            if (spanWidth(text, lineStart, wordEnd) <= maxWidth) {
                lineEnd = wordEnd;
            } else {
                if (lineEnd >= 0) {
                    addLine(text, lineStart, lineEnd);
                    lineStart = wordStart;
                    lineEnd = -1;
                }
                while (spanWidth(text, lineStart, wordEnd) > maxWidth && wordEnd - lineStart > 1) {
                    int cut = lineStart + 1;
                    while (cut < wordEnd && spanWidth(text, lineStart, cut + 1) <= maxWidth) cut++;
                    addLine(text, lineStart, cut);
                    lineStart = cut;
                }
                lineEnd = wordEnd;
            }
            wordStart = wordEnd + 1;
        }
        addLine(text, lineStart, lineEnd < 0 ? to : lineEnd);
    }

    private void addLine(String text, int start, int end) {
        lines.add(text.substring(start, end));
        lineStarts.add(start);
        lineSources.add(end - start);
    }

    private float spanWidth(String text, int start, int end) {
        float width = 0f;
        int i = start;
        while (i < end) {
            int flag = flags[i];
            int j = i + 1;
            while (j < end && flags[j] == flag) j++;
            width += fontFor(flag).measureTextWidth(text.substring(i, j));
            i = j;
        }
        return width;
    }

    /**
     * The font for a combination of BOLD, ITALIC and CODE: the overlay's own for none, else one made from it.
     */
    private Font fontFor(int flag) {
        Font base = getOrBuildFont();
        if (flag == 0) return base;
        if (base != runBase) {
            closeRunFonts();
            runBase = base;
        }
        Font font = runFonts[flag];
        if (font == null) {
            boolean code = (flag & CODE) != 0;
            font = new Font(code ? me.piitex.engine.ui.text.Font.monospace() : base.getTypeface(), code ? base.getSize() * 0.92f : base.getSize());
            font.setEmboldened(base.isEmboldened() || (flag & BOLD) != 0);
            font.setSkewX((flag & ITALIC) != 0 ? ITALIC_SKEW : base.getSkewX());
            runFonts[flag] = font;
        }
        return font;
    }

    private void closeRunFonts() {
        for (int i = 0; i < runFonts.length; i++) {
            if (runFonts[i] != null) runFonts[i].close();
            runFonts[i] = null;
        }
    }

    @Override
    public void dispose() {
        closeRunFonts();
        runBase = null;
        super.dispose();
    }

    /** One stretch of a line drawn in one style. */
    private record Segment(float x, String text, int flag, int link, float width) {
    }

    /**
     * A line cut into stretches of one style each, with the x each starts at. The line's own alignment is applied.
     */
    private List<Segment> segments(int line) {
        String text = lines.get(line);
        int start = lineStarts.get(line), source = lineSources.get(line);
        float innerWidth = (float) getWidth() - 2 * padding - (reserveBar ? barWidth() : 0f);
        List<Segment> out = new ArrayList<>();
        float total = 0f;
        int i = 0;
        while (i < text.length()) {
            int flag = i < source ? flags[start + i] : 0, link = i < source ? linkOf[start + i] : 0;
            int j = i + 1;
            while (j < text.length() && (j < source ? flags[start + j] : 0) == flag && (j < source ? linkOf[start + j] : 0) == link) j++;
            String piece = text.substring(i, j);
            float width = fontFor(flag).measureTextWidth(piece);
            out.add(new Segment(total, piece, flag, link, width));
            total += width;
            i = j;
        }
        float x = (float) getX() + padding + (innerWidth - total) * textAlignH(textAlign.fraction());
        List<Segment> placed = new ArrayList<>(out.size());
        for (Segment segment : out) placed.add(new Segment(x + segment.x(), segment.text(), segment.flag(), segment.link(), segment.width()));
        return placed;
    }

    /**
     * The address of the link under a point in this element's own coordinate space, or null.
     */
    String hrefAt(double lx, double ly) {
        if (lines.isEmpty() || lineStarts.size() != lines.size() || hrefs.isEmpty()) return null;
        if (lx < getX() || lx > getX() + getWidth() || ly < getY() || ly > getY() + getHeight()) return null;
        int line = (int) Math.floor((ly - getY() - padding + scrollOffsetY) / linePitch());
        if (line < 0 || line >= lines.size()) return null;
        for (Segment segment : segments(line)) {
            if (segment.link() != 0 && lx >= segment.x() && lx < segment.x() + segment.width()) return hrefs.get(segment.link() - 1);
        }
        return null;
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
            if (lineStarts.size() == lines.size()) {
                drawStyledLine(renderer, i, y);
                continue;
            }
            float x = (float) getX() + padding;
            float slack = innerWidth - font.measureTextWidth(line);
            float natural = textAlign.fraction();
            x += slack * textAlignH(natural);
            renderer.drawText(line, x, y, font, resolveTextColor(getTextColor()));
        }
        renderer.popClip();

        if (scrollable && scrollbarVisible) drawScrollbar(renderer);
    }

    private void drawStyledLine(Renderer renderer, int line, float y) {
        Paint color = resolveTextColor(getTextColor());
        float pitch = linePitch();
        for (Segment segment : segments(line)) {
            boolean code = (segment.flag() & CODE) != 0;
            if (code) {
                renderer.drawQuad(segment.x() - 1f, y, segment.width() + 2f, pitch - lineSpacing, new Color(0.5f, 0.5f, 0.5f, 0.2f), 3f);
            }
            Paint segmentColor = segment.link() != 0 ? ThemeManager.getCurrent().getAccent() : color;
            Font font = fontFor(segment.flag());
            renderer.drawText(segment.text(), segment.x(), y, font, segmentColor);
            if (segment.link() != 0) {
                float baseline = y - FontMetricsCompat.of(font).getAscent() + 2f;
                renderer.drawLine(segment.x(), baseline, segment.x() + segment.width(), baseline, segmentColor, Math.max(1f, font.getSize() / 14f));
            }
        }
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
