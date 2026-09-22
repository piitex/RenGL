package me.piitex.engine.ui.overlays;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.lwjgl.glfw.GLFW;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.TextChecker;
import me.piitex.engine.ui.text.TextIssue;
import me.piitex.engine.ui.text.TextSpan;
import me.piitex.engine.ui.text.TextSpanStyler;
import org.jetbrains.skia.FontMetrics;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The advanced {@link TextAreaOverlay}. Everything about the plain one, including word
 * wrap, selection, clipboard support, and multi-line editing, works the same. This
 * class adds an optional, application-supplied {@link TextSpanStyler} that colors runs
 * of text, such as an AI chat transcript where "quoted" text and *emphasized* text each
 * get their own color, without hard-coding any specific syntax into the engine. See
 * {@link TextSpanStyler}'s own docs. With no styler set, the default from
 * {@link #getStyler()}, this draws exactly like a plain {@link TextAreaOverlay}.
 * <p>
 * An optional {@link TextChecker} (see {@link #setChecker}) adds spell and grammar
 * checking. Once the text has stopped changing for {@link #CHECK_DEBOUNCE_MS}, it is
 * checked on a background thread and each issue found is drawn as a squiggly underline.
 * The debounce keeps a half-typed word from being flagged. Underlines are independent
 * of the styler's colors, and both can be active at once.
 */
public class RichTextAreaOverlay extends TextAreaOverlay {
    private TextSpanStyler styler;

    private String cachedText;
    private List<TextSpan> cachedSpans = List.of();

    /**
     * How long the text must sit unchanged before it is sent to the checker.
     */
    public static final long CHECK_DEBOUNCE_MS = 400;

    private static final ScheduledExecutorService CHECK_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rengl-text-checker");
        t.setDaemon(true);
        return t;
    });

    private static final Color SPELLING_COLOR = new Color(0.9f, 0.2f, 0.2f, 1f);
    private static final Color GRAMMAR_COLOR = new Color(0.25f, 0.5f, 0.95f, 1f);

    /**
     * The text a batch of issues was computed for, together with those issues, so stale ones can be told apart.
     */
    private record CheckResult(String text, List<TextIssue> issues) {
    }

    private volatile TextChecker checker;
    private volatile CheckResult checkResult = new CheckResult("", List.of());
    private String lastScheduledText;
    private ScheduledFuture<?> pendingCheck;

    {
        // Right-clicking a flagged word offers corrections. Registered here (an instance
        // initializer) so every constructor gets it without repeating the call.
        onMouseClick(event -> {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                openSuggestions(event.getMouseX(), event.getMouseY());
            }
        });
    }

    public RichTextAreaOverlay(double width, double height) {
        super(width, height);
    }

    public RichTextAreaOverlay(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    public RichTextAreaOverlay(double x, double y, double width, double height, float fontSize, Paint textColor) {
        super(x, y, width, height, fontSize, textColor);
    }

    public RichTextAreaOverlay(double x, double y, double width, double height, File fontFile, float fontSize, Paint textColor) {
        super(x, y, width, height, fontFile, fontSize, textColor);
    }

    /**
     * The styler currently coloring this box's text, or null (the default) if it's
     * drawing as one solid color like a plain {@link TextAreaOverlay}.
     */
    public TextSpanStyler getStyler() {
        return styler;
    }

    /**
     * Sets the styler, or clears it when passed null. See the class docs. This takes effect on the next render; spans are recomputed lazily whenever the text has changed since the last draw, rather than on every frame.
     */
    public void setStyler(TextSpanStyler styler) {
        this.styler = styler;
        cachedText = null; // force a recompute on the next draw regardless of whether the text itself changed
    }

    public TextChecker getChecker() {
        return checker;
    }

    /**
     * Sets the spell and grammar checker, or clears it when passed null. See the class docs.
     */
    public void setChecker(TextChecker checker) {
        this.checker = checker;
        this.checkResult = new CheckResult("", List.of());
        this.lastScheduledText = null;
        if (pendingCheck != null) pendingCheck.cancel(false);
    }

    /**
     * Draws the line's text (colored per the styler if one is set), then any spelling or
     * grammar underlines over it.
     */
    @Override
    protected void drawLineText(Renderer renderer, String lineText, int lineStart, float x, float y, org.jetbrains.skia.Font font) {
        TextChecker activeChecker = checker;
        if (activeChecker != null) scheduleCheckIfNeeded(activeChecker, getText());

        drawStyledText(renderer, lineText, lineStart, x, y, font);

        if (activeChecker != null) drawIssues(renderer, lineText, lineStart, x, y, font);
    }

    /**
     * The still-valid issue whose word is at window-space (x, y), or null. This uses the same staleness rule as {@link #drawIssues}.
     */
    private TextIssue issueAt(double x, double y) {
        CheckResult result = checkResult;
        String current = getText();
        int index = indexAtPoint(x, y);
        for (TextIssue issue : result.issues()) {
            if (index < issue.start() || index > issue.end()) continue;
            int len = issue.end() - issue.start();
            if (issue.end() <= current.length() && current.regionMatches(issue.start(), result.text(), issue.start(), len)) {
                return issue;
            }
        }
        return null;
    }

    /**
     * Shows the correction list for a flagged word under (x, y), if there is one.
     */
    private void openSuggestions(double x, double y) {
        TextChecker activeChecker = checker;
        Window window = getWindow();
        if (activeChecker == null || window == null) return;

        TextIssue issue = issueAt(x, y);
        if (issue == null) return;

        String current = getText();
        List<String> suggestions = activeChecker.suggest(current, issue);
        if (suggestions.size() > SuggestionPopup.MAX_ROWS)
            suggestions = suggestions.subList(0, SuggestionPopup.MAX_ROWS);

        SuggestionPopup popup = new SuggestionPopup(issue, current.substring(issue.start(), issue.end()), suggestions);
        float w = popup.preferredWidth();
        float h = popup.preferredHeight();
        float px = (float) Math.min(x, window.getWindowOptions().getWidth() - w);
        float py = (float) Math.min(y, window.getWindowOptions().getHeight() - h);
        popup.setBounds(Math.max(0, px), Math.max(0, py), w, h);
        window.showPopup(popup, popup::closeFont);
    }

    /**
     * The floating list of corrections. Like DropdownOverlay's list, a Container that
     * draws and hit-tests its own rows. Window#showPopup dismisses it on an outside
     * click or on Escape. Choosing a row replaces the misspelled word with it.
     */
    private class SuggestionPopup extends Container {
        static final int MAX_ROWS = 6;
        private static final float FONT_SIZE = 16f;
        private static final float ROW_HEIGHT = 28f;
        private static final float PADDING_X = 10f;

        private final TextIssue issue;
        private final String word; // the word as it was when the menu opened, which guards against the text changing underneath
        private final List<String> rows;
        private final boolean empty;
        private org.jetbrains.skia.Font font;

        SuggestionPopup(TextIssue issue, String word, List<String> suggestions) {
            super(0, 0);
            this.issue = issue;
            this.word = word;
            this.empty = suggestions.isEmpty();
            this.rows = empty ? List.of("No suggestions") : suggestions;
            setCursorMode(CursorMode.POINTER);
            onMouseClick(event -> {
                int row = (int) Math.floor((event.getMouseY() - getY()) / ROW_HEIGHT);
                if (empty || row < 0 || row >= rows.size()) return;
                Window window = getWindow();
                String current = getText();
                if (issue.end() <= current.length() && current.regionMatches(issue.start(), word, 0, word.length())) {
                    replaceRange(issue.start(), issue.end(), rows.get(row));
                }
                if (window != null) window.closePopup();
            });
        }

        private org.jetbrains.skia.Font font() {
            if (font == null) font = new org.jetbrains.skia.Font(Font.getDefault(), FONT_SIZE);
            return font;
        }

        void closeFont() {
            if (font != null) {
                font.close();
                font = null;
            }
        }

        float preferredWidth() {
            float widest = 0f;
            for (String row : rows) widest = Math.max(widest, font().measureTextWidth(row));
            return Math.max(120f, widest + PADDING_X * 2);
        }

        float preferredHeight() {
            return rows.size() * ROW_HEIGHT;
        }

        @Override
        public void render(Renderer renderer) {
            Theme theme = ThemeManager.getCurrent();
            float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
            float radius = theme.getCornerRadius();

            renderer.drawQuad(x, y, w, h, theme.getFieldBackground(), radius);

            renderer.pushClip(x, y, w, h);
            int hoverRow = !empty && MouseTracker.isInWindow() && contains(MouseTracker.getX(), MouseTracker.getY())
                    ? (int) Math.floor((MouseTracker.getY() - y) / ROW_HEIGHT) : -1;
            org.jetbrains.skia.Font f = font();
            float textHeight = f.getMetrics().getHeight();
            for (int i = 0; i < rows.size(); i++) {
                float rowY = y + i * ROW_HEIGHT;
                if (i == hoverRow) renderer.drawQuad(x, rowY, w, ROW_HEIGHT, theme.getFieldHoverBackground(), 0f);
                renderer.drawText(rows.get(i), x + PADDING_X, rowY + (ROW_HEIGHT - textHeight) / 2f, f, theme.getText());
            }
            renderer.popClip();

            renderer.drawBorder(x, y, w, h, theme.getFieldBorder(), Math.max(1f, theme.getBorderThickness()), radius);
        }
    }

    /**
     * (Re)starts the debounce timer whenever the text differs from the last text handed to the checker.
     */
    private void scheduleCheckIfNeeded(TextChecker activeChecker, String text) {
        if (text.equals(lastScheduledText)) return;
        lastScheduledText = text;
        if (pendingCheck != null) pendingCheck.cancel(false);
        pendingCheck = CHECK_EXECUTOR.schedule(() -> {
            try {
                checkResult = new CheckResult(text, activeChecker.check(text));
            } catch (RuntimeException e) {
                checkResult = new CheckResult(text, List.of()); // a broken checker shouldn't take the UI down
            }
        }, CHECK_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void drawIssues(Renderer renderer, String lineText, int lineStart, float x, float y, org.jetbrains.skia.Font font) {
        CheckResult result = checkResult;
        if (result.issues().isEmpty()) return;

        String current = getText();
        int lineEnd = lineStart + lineText.length();
        FontMetrics metrics = font.getMetrics();
        float underlineY = y - metrics.getAscent() + metrics.getDescent() * 0.6f;

        for (TextIssue issue : result.issues()) {
            if (issue.end() <= lineStart || issue.start() >= lineEnd) continue;
            // Results lag edits by the debounce, so only draw an issue whose word is still exactly what was checked.
            int len = issue.end() - issue.start();
            if (issue.end() > current.length() || !current.regionMatches(issue.start(), result.text(), issue.start(), len))
                continue;

            int start = Math.max(issue.start(), lineStart);
            int end = Math.min(issue.end(), lineEnd);
            float x0 = x + font.measureTextWidth(lineText.substring(0, start - lineStart));
            float x1 = x + font.measureTextWidth(lineText.substring(0, end - lineStart));
            drawSquiggle(renderer, x0, x1, underlineY, issue.kind() == TextIssue.Kind.GRAMMAR ? GRAMMAR_COLOR : SPELLING_COLOR);
        }
    }

    /**
     * A zigzag underline from {@code x0} to {@code x1} centered on {@code y}.
     */
    private void drawSquiggle(Renderer renderer, float x0, float x1, float y, Color color) {
        final float step = 2.5f, amplitude = 1.5f;
        float px = x0, py = y;
        boolean up = true;
        while (px < x1) {
            float nx = Math.min(px + step, x1);
            float ny = y + (up ? -amplitude : amplitude);
            renderer.drawLine(px, py, nx, ny, color, 1f);
            px = nx;
            py = ny;
            up = !up;
        }
    }

    /**
     * Splits {@code lineText} into runs per {@link #getStyler()}'s spans (recomputing
     * them first if the text has changed since the last draw) and draws each in its
     * own color, falling back to the plain solid-color draw when no styler is set.
     */
    private void drawStyledText(Renderer renderer, String lineText, int lineStart, float x, float y, org.jetbrains.skia.Font font) {
        if (styler == null) {
            super.drawLineText(renderer, lineText, lineStart, x, y, font);
            return;
        }

        String currentText = getText();
        if (!currentText.equals(cachedText)) {
            cachedText = currentText;
            List<TextSpan> spans = styler.style(currentText);
            List<TextSpan> sorted = new ArrayList<>(spans == null ? List.of() : spans);
            sorted.sort(Comparator.comparingInt(TextSpan::start));
            cachedSpans = sorted;
        }

        int lineEnd = lineStart + lineText.length();
        Paint defaultColor = resolveTextColor(getTextColor());
        float cursorX = x;
        int pos = lineStart;

        for (TextSpan span : cachedSpans) {
            if (span.end() <= lineStart || span.start() >= lineEnd) continue; // doesn't touch this line at all

            int start = Math.max(span.start(), pos);
            int end = Math.min(span.end(), lineEnd);
            if (start >= end) continue;

            if (start > pos) {
                cursorX = drawRun(renderer, lineText.substring(pos - lineStart, start - lineStart), cursorX, y, font, defaultColor);
            }

            Paint color = span.color() != null ? span.color() : defaultColor;
            cursorX = drawRun(renderer, lineText.substring(start - lineStart, end - lineStart), cursorX, y, font, color);
            pos = end;
        }

        if (pos < lineEnd) {
            drawRun(renderer, lineText.substring(pos - lineStart), cursorX, y, font, defaultColor);
        }
    }

    /**
     * Draws {@code run} at {@code x} (a no-op if empty or {@code color} is null, matching every other draw-if-non-null convention in this engine) and returns the x position right after it, for the next run to continue from.
     */
    private float drawRun(Renderer renderer, String run, float x, float y, org.jetbrains.skia.Font font, Paint color) {
        if (run.isEmpty() || color == null) return x;
        renderer.drawText(run, x, y, font, color);
        return x + font.measureTextWidth(run);
    }
}
