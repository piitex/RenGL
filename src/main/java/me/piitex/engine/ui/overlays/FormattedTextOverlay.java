package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.text.FormattedBlock;
import me.piitex.engine.ui.text.TextFormatPreset;
import me.piitex.engine.ui.text.TextStyle;

import java.util.ArrayList;
import java.util.List;

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
 * {@link #setSource} re-parses and rebuilds every block from scratch, which is fine for
 * a document set once (or occasionally, e.g. switching locale), not meant to be called
 * on every keystroke of a live edit.
 */
public class FormattedTextOverlay extends ScrollContainer {
    private final TextFormatPreset preset;
    private String source = "";
    private final List<TextFlowOverlay> blocks = new ArrayList<>();

    public FormattedTextOverlay(double width, double height, TextFormatPreset preset) {
        super(width, height);
        this.preset = preset;
    }

    public FormattedTextOverlay(double x, double y, double width, double height, TextFormatPreset preset) {
        super(x, y, width, height);
        this.preset = preset;
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

    /**
     * Re-wraps every block at the new width.
     */
    @Override
    public void setWidth(double width) {
        super.setWidth(width);
        // super(...) (Container's constructor) sets the initial width, which dispatches
        // here virtually before this class's own fields (preset, blocks) are
        // initialized. Skip that call; the constructor sets preset immediately after
        // super() returns, and the first real rebuild happens on setSource(...).
        if (preset != null) rebuild();
    }

    private void rebuild() {
        List<FormattedBlock> parsed = preset.parse(source);

        double contentHeight = layoutBlocks(parsed, getWidth());
        // Mirrors TextFlowOverlay.updateComputedSize()'s own two-pass measure: lay out once
        // at full width, and only if that overflows the viewport (so our own scrollbar will
        // actually be drawn) re-wrap narrower to leave it a clear gutter. Doing this
        // unconditionally would needlessly shrink the wrap width for content that never
        // scrolls at all.
        if (isVerticalScrollEnabled() && isScrollbarVisible() && contentHeight > getHeight()) {
            double barWidth = getScrollBarStyle().getThickness() + 2 * getScrollBarStyle().getMargin();
            layoutBlocks(parsed, getWidth() - barWidth);
        }

        scrollTo(getScrollOffsetX(), 0);
    }

    private double layoutBlocks(List<FormattedBlock> parsed, double width) {
        for (TextFlowOverlay block : blocks) {
            removeElement(block);
        }
        blocks.clear();

        double cursorY = 0;
        for (FormattedBlock block : parsed) {
            TextStyle style = block.style();
            cursorY += block.spacingBefore();

            TextFlowOverlay flow = new TextFlowOverlay(block.text(), width, style.fontSize(),
                    style.color() != null ? style.color() : Color.BLACK);
            flow.setBold(style.bold());
            flow.setItalic(style.italic());
            flow.setPosition(0, cursorY);
            addElement(flow);
            blocks.add(flow);

            cursorY += flow.getHeight() + block.spacingAfter();
        }
        return cursorY;
    }
}
