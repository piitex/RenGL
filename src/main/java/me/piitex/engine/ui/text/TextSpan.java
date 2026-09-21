package me.piitex.engine.ui.text;

import me.piitex.engine.ui.color.Paint;

/**
 * One colored run within a {@link me.piitex.engine.ui.overlays.RichTextAreaOverlay}'s
 * text. {@code [start, end)} are flat codepoint-count indices into the whole text,
 * the same coordinate space {@code cursorIndex} uses internally, matching how
 * {@link String#substring(int, int)} already indexes. Produced by an app-supplied
 * {@link TextSpanStyler}; this engine ships no built-in syntax rules (no markdown,
 * no quote or asterisk handling). See that interface's docs for why.
 *
 * @param start inclusive start index
 * @param end   exclusive end index
 * @param color the run's color. Null falls back to whatever the text area's own
 *              {@link TextEditingOptions#getTextColor()} is at draw time, same as an
 *              unstyled gap between spans
 */
public record TextSpan(int start, int end, Paint color) {
}
