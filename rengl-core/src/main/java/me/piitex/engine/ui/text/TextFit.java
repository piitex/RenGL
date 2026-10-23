package me.piitex.engine.ui.text;

import org.jetbrains.skia.Font;

/**
 * Shortening text to fit a width, for a file name or a status line that may be longer than the room it has.
 */
public final class TextFit {
    private static final String ELLIPSIS = "…";

    private TextFit() {
    }

    /**
     * {@code text} as it is if it measures no wider than {@code maxWidth} in {@code font}, otherwise cut short and
     * ended with an ellipsis ({@code …}) so it fits. Empty if not even the ellipsis fits. A surrogate pair is never
     * split.
     */
    public static String ellipsize(String text, Font font, float maxWidth) {
        return ellipsize(text, font, maxWidth, false);
    }

    /**
     * As {@link #ellipsize(String, Font, float)}. With {@code middle} the ellipsis goes in the middle and both ends
     * stay, so {@code "quarterly-report-final-v2.xlsx"} becomes {@code "quarterly-re…v2.xlsx"} and keeps its extension,
     * which is what a person looks for in a file name.
     */
    public static String ellipsize(String text, Font font, float maxWidth, boolean middle) {
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 0) return "";
        if (font.measureTextWidth(text) <= maxWidth) return text;

        float budget = maxWidth - font.measureTextWidth(ELLIPSIS);
        if (budget <= 0) return "";
        return middle ? middle(text, font, budget) : end(text, font, budget);
    }

    private static String end(String text, Font font, float budget) {
        int keep = longestPrefix(text, font, budget);
        return text.substring(0, keep).stripTrailing() + ELLIPSIS;
    }

    /** The start gets the larger half of the room, the end the rest, so the extension and the first letters both stay. */
    private static String middle(String text, Font font, float budget) {
        float tailBudget = budget * 0.4f;
        int tail = longestSuffix(text, font, tailBudget);
        int head = longestPrefix(text, font, budget - font.measureTextWidth(text.substring(text.length() - tail)));
        if (head + tail >= text.length()) head = Math.max(0, text.length() - tail - 1);
        return text.substring(0, head).stripTrailing() + ELLIPSIS + text.substring(text.length() - tail).stripLeading();
    }

    /** How many chars from the start measure within {@code budget}. Never ends inside a surrogate pair. */
    private static int longestPrefix(String text, Font font, float budget) {
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (font.measureTextWidth(text.substring(0, prefixEnd(text, mid))) <= budget) low = mid;
            else high = mid - 1;
        }
        return prefixEnd(text, low);
    }

    /** How many chars from the end measure within {@code budget}. Never starts inside a surrogate pair. */
    private static int longestSuffix(String text, Font font, float budget) {
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (font.measureTextWidth(text.substring(suffixStart(text, mid))) <= budget) low = mid;
            else high = mid - 1;
        }
        return text.length() - suffixStart(text, low);
    }

    // Half a pair is not valid text, and measuring it brings Skia down, so every cut is moved off the middle of one.

    private static int prefixEnd(String text, int end) {
        return end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) ? end - 1 : end;
    }

    private static int suffixStart(String text, int count) {
        int start = text.length() - count;
        return start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start)) ? start + 1 : start;
    }
}
