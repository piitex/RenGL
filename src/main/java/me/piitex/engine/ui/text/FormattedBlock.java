package me.piitex.engine.ui.text;

/**
 * One block-level unit of a {@link TextFormatPreset}'s output, such as a heading or a
 * paragraph: a run of plain text drawn at its own {@link TextStyle}, stacked vertically
 * with the blocks around it (see {@link me.piitex.engine.ui.overlays.FormattedTextOverlay}).
 * {@code spacingBefore}/{@code spacingAfter} are the gaps this block wants above and
 * below itself, so a preset can give a heading more breathing room than a plain
 * paragraph without the consumer having to know which block is which. There is no
 * inline styling within a block; mixing styles mid-paragraph means splitting into more
 * blocks.
 *
 * @param text          the block's plain text, already stripped of markup
 * @param style         the size/weight/slant/color to draw the whole block in
 * @param spacingBefore gap, in points, left above this block
 * @param spacingAfter  gap, in points, left below this block
 */
public record FormattedBlock(String text, TextStyle style, float spacingBefore, float spacingAfter) {
}
