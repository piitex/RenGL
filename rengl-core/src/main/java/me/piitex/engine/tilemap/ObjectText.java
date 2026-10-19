package me.piitex.engine.tilemap;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.TextAlign;

/**
 * What a Tiled text object says and how it is set: the text, and the font, size, color and alignment chosen for it in
 * Tiled. The text is drawn in its box, clipped to it, when the layer it is on is drawn.
 *
 * @param text       the words; a line break is kept
 * @param fontFamily the font family chosen in Tiled. The engine draws every text object in its
 *                   {@linkplain me.piitex.engine.ui.text.Font#getDefault() default font}, so this is only for a game that wants to pick a font itself
 * @param pixelSize  the size of the letters, in pixels
 * @param wrap       whether lines are broken to fit the box's width
 * @param color      the text color
 * @param bold       drawn bold
 * @param italic     drawn slanted
 * @param underline  drawn with a line under it
 * @param strikeout  drawn with a line through it
 * @param horizontal where in the box's width each line sits
 * @param vertical   where in the box's height the text sits: 0 at the top, 0.5 in the middle, 1 at the bottom
 */
public record ObjectText(String text, String fontFamily, float pixelSize, boolean wrap, Color color, boolean bold,
                         boolean italic, boolean underline, boolean strikeout, TextAlign horizontal, float vertical) {
    public ObjectText {
        text = text == null ? "" : text;
        fontFamily = fontFamily == null ? "sans-serif" : fontFamily;
        pixelSize = pixelSize > 0f ? pixelSize : 16f;
        color = color == null ? Color.BLACK : color;
        horizontal = horizontal == null ? TextAlign.LEFT : horizontal;
        vertical = Math.max(0f, Math.min(1f, vertical));
    }
}
