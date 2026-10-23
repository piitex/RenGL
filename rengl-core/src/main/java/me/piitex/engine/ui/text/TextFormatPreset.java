package me.piitex.engine.ui.text;

import java.util.List;

/**
 * Turns raw marked-up source into a list of styled {@link FormattedBlock}s, ready to be
 * stacked and drawn by {@link me.piitex.engine.ui.overlays.FormattedTextOverlay}. Unlike
 * {@link TextSpanStyler} (which only recolors runs of an existing string in place), a
 * preset owns both the syntax and the resulting sizes/weights: it decides what counts as
 * a heading, and how big one should be.
 * <p>
 * This engine ships two: {@link MarkdownFormatPreset} ({@code #}/{@code ##}/{@code ###}
 * line prefixes) and {@link BracketFormatPreset} ({@code [heading=1]...[/heading]}).
 * Neither is privileged over the other or over a custom implementation; an application
 * with its own syntax, its own heading sizes, or inline emphasis this engine doesn't
 * attempt, implements this interface directly instead of being stuck with either
 * default.
 */
@FunctionalInterface
public interface TextFormatPreset {
    List<FormattedBlock> parse(String source);
}
