package me.piitex.engine.ui.text;

import java.util.List;

/**
 * Turns a {@link me.piitex.engine.ui.overlays.RichTextAreaOverlay}'s current text into
 * a list of colored {@link TextSpan}s. One styler for an AI chat transcript might color
 * "quoted" runs one way and *asterisk-wrapped* runs another. It is a function from text
 * to spans, with no specific inline-coloring syntax baked into this engine. An
 * application wanting that markup implements this interface itself, wrapping a regex or
 * a parser as it sees fit, and hands the instance to {@link
 * me.piitex.engine.ui.overlays.RichTextAreaOverlay#setStyler}. No styler at all (the
 * default) draws every line in one solid color, exactly like the plain {@link
 * me.piitex.engine.ui.overlays.TextAreaOverlay}.
 * <p>
 * This is deliberately narrower than {@link TextFormatPreset}: a span only ever recolors
 * a run of the existing text, never changes its size or weight, and cannot introduce
 * block-level structure like a heading. For that, see {@link TextFormatPreset} and
 * {@link me.piitex.engine.ui.overlays.FormattedTextOverlay}, which this engine does ship
 * two ready-made syntaxes for.
 * <p>
 * Re-run on every draw where the text has changed since the last call (see {@link
 * me.piitex.engine.ui.overlays.RichTextAreaOverlay}'s own caching), so keep it cheap for
 * anything typed into interactively. A full spell-check pass or a network call does not
 * belong here.
 * <p>
 * Spans should be sorted by {@link TextSpan#start()} and non-overlapping; malformed
 * input degrades to a rendering glitch rather than a crash, but that is not a guarantee
 * this interface is meant to be relied on for.
 */
@FunctionalInterface
public interface TextSpanStyler {
    List<TextSpan> style(String text);
}
