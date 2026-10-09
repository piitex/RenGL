package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.text.FormattedBlock;
import me.piitex.engine.ui.text.TextFormatPreset;
import me.piitex.engine.ui.text.TextStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Renders long, mixed-heading-and-paragraph text, such as a TOS document or a changelog,
 * from raw marked-up source, via a pluggable {@link TextFormatPreset} (see {@link
 * me.piitex.engine.ui.text.MarkdownFormatPreset} and {@link
 * me.piitex.engine.ui.text.BracketFormatPreset}, or supply your own for a different
 * syntax or sizing scheme).
 * <p>
 * Built on {@link ScrollContainer} for the scrolling and scrollbar, and on one {@link
 * TextFlowOverlay} per {@link FormattedBlock} for the word wrap, so each block sizes,
 * wraps, and scrolls exactly as a plain TextFlowOverlay already does. Blocks are stacked
 * and positioned by hand from each one's own height and its {@link
 * FormattedBlock#spacingBefore()}/{@link FormattedBlock#spacingAfter()}, not through a
 * {@link me.piitex.engine.ui.layout.Layout}: a Layout re-runs its own uniform-spacing
 * formula every frame and has no way to give a heading a bigger gap than a paragraph.
 * <p>
 * {@link #setSource} and {@link #setPreset} parse the source and build every block. Changing the width or height
 * afterwards only wraps the blocks again, without parsing, so resizing the overlay is cheap enough to do while the
 * user drags. Links in the text open through {@link #setLinkHandler}.
 */
public class FormattedTextOverlay extends ScrollContainer {
    private TextFormatPreset preset;
    private String source = "";
    private final List<Entry> entries = new ArrayList<>();
    private java.util.function.Consumer<String> linkHandler;
    private boolean built; // the first layout waits for the fields below the constructor's call to setWidth

    /** A block with the elements drawn for it. */
    private record Entry(FormattedBlock block, TextOverlay marker, TextFlowOverlay flow) {
    }

    public FormattedTextOverlay(double width, double height, TextFormatPreset preset) {
        super(width, height);
        this.preset = Objects.requireNonNull(preset, "preset");
        built = true;
    }

    public FormattedTextOverlay(double x, double y, double width, double height, TextFormatPreset preset) {
        super(x, y, width, height);
        this.preset = Objects.requireNonNull(preset, "preset");
        built = true;
    }

    public String getSource() {
        return source;
    }

    /**
     * Parses {@code source} through this overlay's {@link TextFormatPreset} and rebuilds every block. Scroll position resets to the top.
     */
    public void setSource(String source) {
        this.source = source == null ? "" : source;
        rebuild();
    }

    public TextFormatPreset getPreset() {
        return preset;
    }

    /**
     * Switches to another syntax or sizing scheme and parses the current source again with it. Scroll position resets to the top.
     */
    public void setPreset(TextFormatPreset preset) {
        this.preset = Objects.requireNonNull(preset, "preset");
        rebuild();
    }

    /**
     * What a click on a link does with its address; the default opens web and mail addresses in the system browser (see
     * {@link TextFlowOverlay#setLinkHandler}). Null makes links do nothing.
     */
    public void setLinkHandler(java.util.function.Consumer<String> handler) {
        this.linkHandler = handler;
        for (Entry entry : entries) entry.flow().setLinkHandler(handler == null ? address -> { } : handler);
    }

    /**
     * Wraps every block again at the new width, without parsing the source again.
     */
    @Override
    public void setWidth(double width) {
        super.setWidth(width);
        // super(...) (Container's constructor) sets the initial width, which dispatches here virtually before this
        // class's own fields are initialized; the first real layout is the one setSource does.
        if (built) relayout();
    }

    /**
     * The height decides whether the scrollbar is needed, which narrows the text, so the blocks wrap again.
     */
    @Override
    public void setHeight(double height) {
        super.setHeight(height);
        if (built) relayout();
    }

    private void rebuild() {
        for (Entry entry : entries) {
            removeElement(entry.flow());
            entry.flow().dispose();
            if (entry.marker() != null) {
                removeElement(entry.marker());
                entry.marker().dispose();
            }
        }
        entries.clear();

        for (FormattedBlock block : preset.parse(source)) {
            TextStyle style = block.style();
            Paint color = style.color() != null ? style.color() : Color.BLACK;

            TextOverlay marker = null;
            if (!block.marker().isEmpty()) {
                marker = new TextOverlay(block.marker(), style.fontSize(), color);
                marker.setBold(style.bold());
                if (style.color() != null) marker.setTextColor(style.color()); // set on purpose, so a theme doesn't take it back
                addElement(marker);
            }

            TextFlowOverlay flow = new TextFlowOverlay(block.text(), 1, style.fontSize(), color);
            flow.setBold(style.bold());
            flow.setItalic(style.italic());
            if (style.color() != null) flow.setTextColor(style.color()); // set on purpose, so a theme doesn't take it back
            if (block.background() != null) {
                flow.setPadding(8f);
                flow.getStyling().setBackgroundColor(block.background());
                flow.getStyling().setCornerRadius(4f);
            }
            if (!block.runs().isEmpty()) flow.setRuns(block.runs());
            if (linkHandler != null) flow.setLinkHandler(linkHandler);
            addElement(flow);
            entries.add(new Entry(block, marker, flow));
        }

        relayout();
        scrollTo(getScrollOffsetX(), 0);
    }

    private void relayout() {
        double contentHeight = layoutBlocks(getWidth());
        // Mirrors TextFlowOverlay.updateComputedSize()'s own two-pass measure: lay out once
        // at full width, and only if that overflows the viewport (so our own scrollbar will
        // actually be drawn) wrap narrower to leave it a clear gutter. Doing this
        // unconditionally would needlessly shrink the wrap width for content that never
        // scrolls at all.
        if (isVerticalScrollEnabled() && isScrollbarVisible() && contentHeight > getHeight()) {
            double barWidth = getScrollBarStyle().getThickness() + 2 * getScrollBarStyle().getMargin();
            layoutBlocks(getWidth() - barWidth);
        }
    }

    private double layoutBlocks(double width) {
        double cursorY = 0;
        for (Entry entry : entries) {
            FormattedBlock block = entry.block();
            cursorY += block.spacingBefore();

            double left = block.indent();
            if (entry.marker() != null) {
                entry.marker().setPosition(left, cursorY);
                left += Math.max(block.style().fontSize() * 1.2, entry.marker().getWidth() + 6);
            }

            entry.flow().setWidth(Math.max(1, width - left));
            entry.flow().setPosition(left, cursorY);

            cursorY += entry.flow().getHeight() + block.spacingAfter();
        }
        return cursorY;
    }
}
