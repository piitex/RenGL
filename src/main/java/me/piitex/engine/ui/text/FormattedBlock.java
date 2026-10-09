package me.piitex.engine.ui.text;

import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.util.List;

/**
 * One block-level unit of a {@link TextFormatPreset}'s output, such as a heading or a
 * paragraph: a run of plain text drawn at its own {@link TextStyle}, stacked vertically
 * with the blocks around it (see {@link me.piitex.engine.ui.overlays.FormattedTextOverlay}).
 * {@code spacingBefore}/{@code spacingAfter} are the gaps this block wants above and
 * below itself, so a preset can give a heading more breathing room than a plain
 * paragraph without the consumer having to know which block is which. The block's
 * {@code style} covers all of its text; {@code runs} mark stretches inside it that are
 * bold, italic, code or a link on top of that.
 * <p>
 * A block can also be a list item, with a {@code marker} in front of its text that the
 * text lines up beside, and a code block, which has a {@code background}. A line break
 * in a block's text is kept, which is what a code block needs.
 *
 * @param text          the block's plain text, already stripped of markup
 * @param style         the size/weight/slant/color to draw the whole block in
 * @param spacingBefore gap, in points, left above this block
 * @param spacingAfter  gap, in points, left below this block
 * @param indent        how far in from the left, in points, the block starts; 0 for none
 * @param marker        what is drawn in front of the text, such as a bullet or "1.", or empty for none
 * @param background    a fill drawn behind the block with a little padding round its text, or null for none
 * @param runs          inline emphasis, code and links within {@code text}; empty for none
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public record FormattedBlock(String text, TextStyle style, float spacingBefore, float spacingAfter, float indent,
                             String marker, Paint background, List<InlineRun> runs) {
    /**
     * A block with no inline runs.
     */
    public FormattedBlock(String text, TextStyle style, float spacingBefore, float spacingAfter, float indent,
                          String marker, Paint background) {
        this(text, style, spacingBefore, spacingAfter, indent, marker, background, List.of());
    }

    /**
     * A plain block: no indent, no marker, no background.
     */
    public FormattedBlock(String text, TextStyle style, float spacingBefore, float spacingAfter) {
        this(text, style, spacingBefore, spacingAfter, 0f, "", null);
    }

    public FormattedBlock {
        marker = marker == null ? "" : marker;
        runs = runs == null ? List.of() : List.copyOf(runs);
    }
}
