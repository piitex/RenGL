package me.piitex.engine.input;

/**
 * The text an input method is still composing, such as kana typed but not yet converted to kanji, which belongs to the
 * input method until the user commits it. It is shown at the caret but is not part of the field's text.
 * <p>
 * An empty {@code text} means the composition ended, whether it was committed (the committed characters arrive as
 * ordinary typed characters) or cancelled.
 *
 * @param text       the composition, empty when there is none
 * @param caret      where the input method's own caret sits in {@code text}, as a UTF-16 index
 * @param focusStart where the clause being converted starts in {@code text}, which the input method draws highlighted
 * @param focusEnd   where that clause ends; equal to {@code focusStart} when none is highlighted
 */
public record ImePreedit(String text, int caret, int focusStart, int focusEnd) {
    public static final ImePreedit NONE = new ImePreedit("", 0, 0, 0);

    public boolean isEmpty() {
        return text.isEmpty();
    }
}
