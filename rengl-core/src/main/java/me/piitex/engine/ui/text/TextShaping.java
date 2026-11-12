package me.piitex.engine.ui.text;

import org.jetbrains.skia.Canvas;
import org.jetbrains.skia.Font;
import org.jetbrains.skia.Paint;
import org.jetbrains.skia.TextLine;
import org.jetbrains.skia.Typeface;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measuring and drawing text that a plain {@link Font} cannot handle on its own: Chinese, Japanese and Korean when the
 * chosen typeface has no such glyphs, right-to-left scripts such as Arabic and Hebrew, and scripts whose letters change
 * shape with their neighbours, such as Thai and the Indic ones.
 * <p>
 * Text made only of Latin letters (everything below U+0300) takes the same path it always has,
 * {@link Font#measureTextWidth} and {@code drawString}, so nothing changes for it. Anything else is run through Skia's
 * shaper (HarfBuzz and ICU underneath): it splits the text into runs by script and direction, picks a system font for
 * every glyph the typeface lacks, and positions the result. The shaped line is cached, since measuring a text field
 * asks for the width of the same text several times a frame.
 * <p>
 * Which fallback fonts exist is up to the operating system. Windows and macOS ship fonts for every major script; a Linux
 * install needs a font such as Noto Sans CJK, or the glyphs show as boxes.
 */
public final class TextShaping {
    private static final int CACHE_ENTRIES = 256;

    private record Key(Typeface typeface, float size, float scaleX, float skewX, String text) {
    }

    private static final Map<Key, TextLine> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, TextLine> eldest) {
            // Not closed on eviction: a line may still be in the middle of a draw. Skia frees it once it is unreachable.
            return size() > CACHE_ENTRIES;
        }
    };

    private static final ThreadLocal<Boolean> FORCED = ThreadLocal.withInitial(() -> false);

    private TextShaping() {
    }

    /**
     * Makes every measure and draw on this thread go through the shaper until the returned scope is closed, even for
     * Latin-only text.
     * <p>
     * Shaped text is a little narrower than the same text measured and drawn plainly, because the shaper uses
     * unrounded advances. A line that needs shaping is measured whole, so anything that then draws pieces of that line
     * has to shape the pieces too, or the pieces add up wider than the line was measured to be. Use it as
     * {@code try (var scope = TextShaping.shapeAll()) { ... }}.
     */
    public static Scope shapeAll() {
        boolean previous = FORCED.get();
        FORCED.set(true);
        return new Scope(previous);
    }

    /**
     * What {@link #shapeAll()} returns. Closing it puts the previous mode back, so scopes can nest.
     */
    public static final class Scope implements AutoCloseable {
        private final boolean previous;

        private Scope(boolean previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            FORCED.set(previous);
        }
    }

    /**
     * Whether {@code text} holds anything a plain font draw cannot be trusted with. False for Latin text, which is
     * the fast path.
     */
    public static boolean needsShaping(CharSequence text) {
        if (text == null) return false;
        if (FORCED.get()) return true;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x0300) return true;
        }
        return false;
    }

    private static TextLine line(Font font, String text) {
        Key key = new Key(font.getTypeface(), font.getSize(), font.getScaleX(), font.getSkewX(), text);
        synchronized (cache) {
            TextLine line = cache.get(key);
            if (line == null) {
                line = TextLine.Companion.make(text, font);
                cache.put(key, line);
            }
            return line;
        }
    }

    /**
     * The width of {@code text} in {@code font}, using fallback fonts and shaping where the text needs them.
     */
    public static float width(Font font, String text) {
        if (text == null || text.isEmpty()) return 0f;
        if (!needsShaping(text)) return font.measureTextWidth(text);
        TextLine line = line(font, text);
        synchronized (cache) {
            return line.getWidth();
        }
    }

    /**
     * How far from the text's left edge the character at {@code offset} (a UTF-16 index) starts, clamped to the text.
     * In right-to-left text this is where the caret goes, which is not to the right of the previous character's.
     */
    public static float xAt(Font font, String text, int offset) {
        if (text == null || text.isEmpty() || offset <= 0) return 0f;
        int clamped = Math.min(offset, text.length());
        if (!needsShaping(text)) return font.measureTextWidth(text.substring(0, clamped));
        TextLine line = line(font, text);
        synchronized (cache) {
            return line.getCoordAtOffset(clamped);
        }
    }

    /**
     * The UTF-16 index of the gap closest to {@code x}, measured from the text's left edge.
     */
    public static int offsetAt(Font font, String text, float x) {
        if (text == null || text.isEmpty() || x <= 0f) return 0;
        if (!needsShaping(text)) {
            int previousIndex = 0;
            float previousWidth = 0f;
            int i = 0;
            while (i < text.length()) {
                int next = i + Character.charCount(text.codePointAt(i));
                float width = font.measureTextWidth(text.substring(0, next));
                if (x < (previousWidth + width) / 2f) return previousIndex;
                previousWidth = width;
                previousIndex = next;
                i = next;
            }
            return text.length();
        }
        TextLine line = line(font, text);
        synchronized (cache) {
            return line.getOffsetAtCoord(x);
        }
    }

    /**
     * Draws {@code text} with its baseline at {@code baselineY}, the way {@code Canvas.drawString} would, but with
     * fallback fonts and shaping where the text needs them.
     */
    public static void draw(Canvas canvas, String text, float x, float baselineY, Font font, Paint paint) {
        if (!needsShaping(text)) {
            canvas.drawString(text, x, baselineY, font, paint);
            return;
        }
        TextLine line = line(font, text);
        synchronized (cache) {
            canvas.drawTextLine(line, x, baselineY, paint);
        }
    }

    /**
     * The index of the character the caret should go to when moving one step from {@code index}: past a whole
     * surrogate pair or a letter with its combining marks as one. {@code direction} is +1 or -1.
     */
    public static int step(String text, int index, int direction) {
        if (text == null || text.isEmpty()) return 0;
        java.text.BreakIterator breaks = java.text.BreakIterator.getCharacterInstance();
        breaks.setText(text);
        int target = direction >= 0 ? breaks.following(Math.min(index, text.length() - 1)) : breaks.preceding(Math.max(index, 1));
        if (target == java.text.BreakIterator.DONE) return direction >= 0 ? text.length() : 0;
        return target;
    }

    /**
     * Whether {@code text} starts in a right-to-left script such as Arabic or Hebrew, which decides which way the
     * arrow keys go and where a caret at the end of the text sits.
     */
    public static boolean isRightToLeft(CharSequence text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            byte directionality = Character.getDirectionality(cp);
            if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
            if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Clears the cache of shaped lines, such as after the system's fonts changed.
     */
    public static void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
