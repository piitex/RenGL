package me.piitex.engine.ui.text;

/**
 * A stretch of a {@link FormattedBlock}'s text that is drawn differently from the rest: bold, italic, in the code
 * font, or as a link. {@code [start, end)} index into the block's text, as {@link String#substring(int, int)} does.
 * Runs may overlap, such as bold inside a link, and every flag of every run that covers a character applies to it.
 *
 * @param start  inclusive start index
 * @param end    exclusive end index
 * @param bold   drawn with a synthesized bold weight
 * @param italic drawn with a synthesized slant
 * @param code   drawn in the monospace font, on a faint background
 * @param href   the address a click on this run opens, or null for none; it is also drawn underlined in the accent color
 */
public record InlineRun(int start, int end, boolean bold, boolean italic, boolean code, String href) {
    public InlineRun {
        if (start < 0 || end < start) throw new IllegalArgumentException("bad run " + start + ".." + end);
    }
}
