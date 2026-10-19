package me.piitex.engine.ui.text;

/**
 * One problem a {@link TextChecker} found. {@code [start, end)} are flat indices into
 * the checked text, in the same coordinate space as {@link TextSpan}.
 *
 * @param start   inclusive start index
 * @param end     exclusive end index
 * @param kind    what sort of problem this is, which decides how it is underlined
 * @param message a short human-readable description (e.g. for a hover tooltip), or null
 */
public record TextIssue(int start, int end, Kind kind, String message) {
    public enum Kind {
        SPELLING,
        GRAMMAR
    }
}
