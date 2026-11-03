package me.piitex.engine.ui.text;

import org.jetbrains.skia.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * Laying out text drawn with {@link me.piitex.engine.gl.renderer.Renderer#drawText Renderer.drawText}: breaking it
 * into lines that fit a width, and how far apart lines go. Wrap once, when the text or the width changes, and draw the
 * lines every frame:
 * <pre>{@code
 * // onEnter, or when the width changes
 * lines = TextLayout.wrap(description, font, 240f);
 *
 * // render
 * renderer.drawTextLines(lines, centerX, top, TextLayout.lineHeight(font), font, Color.WHITE, TextAlign.CENTER);
 * }</pre>
 * {@link me.piitex.engine.ui.overlays.TextFlowOverlay} wraps the same way.
 */
public final class TextLayout {

    private TextLayout() {
    }

    /**
     * Breaks {@code text} into lines no wider than {@code maxWidth} in {@code font}. Lines break between words, and at
     * every {@code '\n'}; a word too wide for a line on its own is broken between characters. An empty text gives no
     * lines, and each blank line in the text gives an empty one.
     */
    public static List<String> wrap(String text, Font font, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        float width = Math.max(1f, maxWidth);
        for (String paragraph : text.split("\n", -1)) wrapParagraph(paragraph, font, width, lines);
        return lines;
    }

    private static void wrapParagraph(String paragraph, Font font, float maxWidth, List<String> lines) {
        if (paragraph.isEmpty()) {
            lines.add("");
            return;
        }

        StringBuilder line = new StringBuilder();
        for (Piece piece : pieces(paragraph)) {
            String word = piece.text();
            String candidate = line.isEmpty() ? word : line + (piece.spaceBefore() ? " " : "") + word;
            if (TextShaping.width(font, candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            // The word alone may still be too wide: break it by character.
            String rest = word;
            while (TextShaping.width(font, rest) > maxWidth && rest.length() > 1) {
                int cut = TextShaping.step(rest, 0, 1);
                while (cut < rest.length()) {
                    int next = TextShaping.step(rest, cut, 1);
                    if (TextShaping.width(font, rest.substring(0, next)) > maxWidth) break;
                    cut = next;
                }
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        lines.add(line.toString());
    }

    /** A stretch of text that may sit at the end of a line, and whether a space separates it from the piece before. */
    private record Piece(String text, boolean spaceBefore) {
    }

    /**
     * Splits at spaces, and, in text that is not Latin, wherever the script allows a line to break: between Chinese and
     * Japanese characters, which are written without spaces, and between Thai words.
     */
    private static List<Piece> pieces(String paragraph) {
        List<Piece> pieces = new ArrayList<>();
        boolean first = true;
        for (String word : paragraph.split(" ", -1)) {
            if (!TextShaping.needsShaping(word) || word.isEmpty()) {
                pieces.add(new Piece(word, !first));
            } else {
                java.text.BreakIterator breaks = java.text.BreakIterator.getLineInstance();
                breaks.setText(word);
                int start = 0;
                for (int end = breaks.next(); end != java.text.BreakIterator.DONE; end = breaks.next()) {
                    pieces.add(new Piece(word.substring(start, end), !first && start == 0));
                    start = end;
                }
            }
            first = false;
        }
        return pieces;
    }

    /**
     * The height of one line of {@code font}, from the top of its tallest letters to the bottom of its lowest: a
     * natural distance between lines. Add a little for looser lines.
     */
    public static float lineHeight(Font font) {
        FontMetricsCompat metrics = FontMetricsCompat.of(font);
        return -metrics.getAscent() + metrics.getDescent();
    }
}
