package me.piitex.engine.ui.text;

import me.piitex.engine.ui.color.Paint;

/**
 * One run's visual style within a {@link TextFormatPreset}'s output: size, weight,
 * slant, and color, independent of whatever the destination overlay's own defaults are.
 * A null {@code color} falls back to the destination overlay's own text color/theme, the
 * same convention {@link TextSpan#color()} uses.
 *
 * @param fontSize the size, in points, text in this style is drawn at
 * @param bold     whether the text is drawn with a synthesized bold weight (see
 *                 {@link me.piitex.engine.ui.overlays.TextOverlay#setBold})
 * @param italic   whether the text is drawn with a synthesized oblique slant (see
 *                 {@link me.piitex.engine.ui.overlays.TextOverlay#setItalic})
 * @param color    the run's color, or null to inherit the destination's own/theme color
 */
public record TextStyle(float fontSize, boolean bold, boolean italic, Paint color) {
    /**
     * A plain, non-bold, non-italic style at {@code fontSize} that inherits its color.
     */
    public static TextStyle of(float fontSize) {
        return new TextStyle(fontSize, false, false, null);
    }

    /**
     * A bold style at {@code fontSize} that inherits its color, the common case for a heading.
     */
    public static TextStyle bold(float fontSize) {
        return new TextStyle(fontSize, true, false, null);
    }
}
