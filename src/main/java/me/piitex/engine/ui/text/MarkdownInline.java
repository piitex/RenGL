package me.piitex.engine.ui.text;

import java.util.ArrayList;
import java.util.List;

/**
 * The inline part of Markdown: {@code **bold**}, {@code *italic*} (and the {@code __} / {@code _} spellings),
 * {@code ***both***}, {@code `code`}, {@code [text](address)} and a backslash to take a mark literally. Parsing gives
 * the plain text with the marks taken out, and an {@link InlineRun} for each stretch they wrapped. Marks that never
 * close, and asterisks with spaces round them (as in {@code 2 * 3 * 4}), stay as written.
 */
final class MarkdownInline {
    /**
     * The text with its marks removed, and the runs over it.
     */
    record Parsed(String text, List<InlineRun> runs) {
    }

    private final String source;
    private final StringBuilder out = new StringBuilder();
    private final List<InlineRun> runs = new ArrayList<>();

    private MarkdownInline(String source) {
        this.source = source;
    }

    static Parsed parse(String source) {
        MarkdownInline parser = new MarkdownInline(source);
        parser.run(0, source.length(), false, false, null);
        return new Parsed(parser.out.toString(), parser.runs);
    }

    private void emit(String text, boolean bold, boolean italic, boolean code, String href) {
        if (text.isEmpty()) return;
        int start = out.length();
        out.append(text);
        if (bold || italic || code || href != null) addRun(new InlineRun(start, out.length(), bold, italic, code, href));
    }

    /**
     * Joins a run onto the one before it when they look the same and meet, so a sentence in bold is one run and
     * not one per character.
     */
    private void addRun(InlineRun run) {
        if (!runs.isEmpty()) {
            InlineRun last = runs.getLast();
            if (last.end() == run.start() && last.bold() == run.bold() && last.italic() == run.italic()
                    && last.code() == run.code() && java.util.Objects.equals(last.href(), run.href())) {
                runs.set(runs.size() - 1, new InlineRun(last.start(), run.end(), run.bold(), run.italic(), run.code(), run.href()));
                return;
            }
        }
        runs.add(run);
    }

    private void run(int from, int to, boolean bold, boolean italic, String href) {
        StringBuilder plain = new StringBuilder();
        int i = from;
        while (i < to) {
            char c = source.charAt(i);

            if (c == '\\' && i + 1 < to && isPunctuation(source.charAt(i + 1))) {
                plain.append(source.charAt(i + 1));
                i += 2;
                continue;
            }

            if (c == '`') {
                int close = source.indexOf('`', i + 1);
                if (close > i + 1 && close < to) {
                    emit(plain.toString(), bold, italic, false, href);
                    plain.setLength(0);
                    emit(source.substring(i + 1, close), bold, italic, true, href);
                    i = close + 1;
                    continue;
                }
            }

            if (c == '[' && href == null) {
                int textEnd = matchingBracket(i, to);
                if (textEnd > 0 && textEnd + 1 < to && source.charAt(textEnd + 1) == '(') {
                    int close = source.indexOf(')', textEnd + 2);
                    if (close > 0 && close < to) {
                        emit(plain.toString(), bold, italic, false, href);
                        plain.setLength(0);
                        String target = source.substring(textEnd + 2, close).strip();
                        run(i + 1, textEnd, bold, italic, target.isEmpty() ? null : target);
                        i = close + 1;
                        continue;
                    }
                }
            }

            if (c == '*' || c == '_') {
                int n = 0;
                while (i + n < to && source.charAt(i + n) == c && n < 3) n++;
                boolean afterWord = i > from && Character.isLetterOrDigit(source.charAt(i - 1));
                boolean followedBySpace = i + n >= to || Character.isWhitespace(source.charAt(i + n));
                if (!afterWord && !followedBySpace) {
                    int close = closing(c, n, i + n, to);
                    if (close > 0) {
                        emit(plain.toString(), bold, italic, false, href);
                        plain.setLength(0);
                        run(i + n, close, bold || n >= 2, italic || n == 1 || n == 3, href);
                        i = close + n;
                        continue;
                    }
                }
                plain.append(source, i, i + n);
                i += n;
                continue;
            }

            plain.append(c);
            i++;
        }
        emit(plain.toString(), bold, italic, false, href);
    }

    /**
     * Where the mark of {@code n} copies of {@code mark} that closes one opened before {@code from} starts, or -1.
     * It must follow a non-space, and an underscore must not sit inside a word.
     */
    private int closing(char mark, int n, int from, int to) {
        for (int j = from; j + n <= to; j++) {
            char c = source.charAt(j);
            if (c == '\\') {
                j++;
                continue;
            }
            if (c == '`') { // a code span hides marks inside it
                int close = source.indexOf('`', j + 1);
                if (close > 0 && close < to) j = close;
                continue;
            }
            if (c != mark) continue;
            int run = 0;
            while (j + run < to && source.charAt(j + run) == mark) run++;
            if (run == n && j > from && !Character.isWhitespace(source.charAt(j - 1))
                    && !(mark == '_' && j + n < to && Character.isLetterOrDigit(source.charAt(j + n)))) {
                return j;
            }
            j += run - 1;
        }
        return -1;
    }

    private int matchingBracket(int open, int to) {
        int depth = 0;
        for (int j = open; j < to; j++) {
            char c = source.charAt(j);
            if (c == '\\') j++;
            else if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return j;
        }
        return -1;
    }

    private static boolean isPunctuation(char c) {
        return "\\`*_[]()#+-.!".indexOf(c) >= 0;
    }
}
