package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.ui.PopupKeyListener;
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
import me.piitex.engine.ui.text.FontMetricsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * The debounce keeps a half-typed word from being flagged. After an edit a checker that
 * {@link TextChecker#checksFragments() checks fragments} sees only the words that changed.
 * Underlines are independent of the styler's colors, and both can be active at once.
 * <p>
 * Right-clicking a flagged word lists corrections, then "Add to dictionary" when the checker
 * {@link TextChecker#canAddWords() can learn words} and "Ignore" to leave that word alone in this box.
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

    private Color spellingColor = new Color(0.9f, 0.2f, 0.2f, 1f);
    private Color grammarColor = new Color(0.25f, 0.5f, 0.95f, 1f);

    /**
     * The text a batch of issues was computed for, together with those issues, so stale ones can be told apart.
     */
    private record CheckResult(String text, List<TextIssue> issues) {
    }

    private volatile TextChecker checker;
    private final Set<String> ignoredWords = ConcurrentHashMap.newKeySet();
    private volatile boolean checkInFull;
    private volatile CheckResult checkResult = new CheckResult("", List.of());
    private String lastScheduledText;
    private ScheduledFuture<?> pendingCheck;

    {
        // Right-clicking a flagged word offers corrections. Registered here (an instance
        // initializer) so every constructor gets it without repeating the call.
        setBuiltInClickListener(event -> {
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
        ignoredWords.clear();
        this.checkResult = new CheckResult("", List.of());
        this.lastScheduledText = null;
        if (pendingCheck != null) pendingCheck.cancel(false);
    }

    /**
     * Stops flagging {@code word} in this box (matched without regard to case), as the "Ignore" row of the correction
     * list does. It lasts until {@link #clearIgnoredWords} or the next {@link #setChecker}, and is not saved.
     */
    public void ignoreWord(String word) {
        ignoredWords.add(word.toLowerCase(Locale.ROOT));
        dropIssuesOn(word);
    }

    /**
     * Flags every word that {@link #ignoreWord} had stopped flagging again, from the next check.
     */
    public void clearIgnoredWords() {
        ignoredWords.clear();
        checkInFull = true;
        lastScheduledText = null;
    }

    /**
     * The color of the squiggle under a misspelled word. Red by default.
     */
    public Color getSpellingColor() {
        return spellingColor;
    }

    public void setSpellingColor(Color spellingColor) {
        this.spellingColor = Objects.requireNonNull(spellingColor, "spellingColor");
    }

    /**
     * The color of the squiggle under a grammar problem. Blue by default.
     */
    public Color getGrammarColor() {
        return grammarColor;
    }

    public void setGrammarColor(Color grammarColor) {
        this.grammarColor = Objects.requireNonNull(grammarColor, "grammarColor");
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
        return issueAtIndex(indexAtPoint(x, y));
    }

    private TextIssue issueAtIndex(int index) {
        CheckResult result = checkResult;
        String current = getText();
        for (TextIssue issue : result.issues()) {
            if (index < issue.start() || index > issue.end()) continue;
            if (stillValid(result, current, issue)) return issue;
        }
        return null;
    }

    private static boolean stillValid(CheckResult result, String current, TextIssue issue) {
        int len = issue.end() - issue.start();
        return issue.end() <= current.length() && current.regionMatches(issue.start(), result.text(), issue.start(), len);
    }

    /* Keyboard and speech */

    private void say(String text) {
        me.piitex.engine.Window window = getWindow();
        if (window != null) window.announce(text, true);
    }

    private static String kindWord(TextIssue issue) {
        return issue.kind() == TextIssue.Kind.GRAMMAR ? "Grammar problem" : "Misspelled";
    }

    /**
     * F8 and Shift+F8: the caret goes to the next or previous flagged word and its problem is read out.
     */
    private void goToIssue(int direction) {
        CheckResult result = checkResult;
        String current = getText();
        List<TextIssue> valid = new ArrayList<>();
        for (TextIssue issue : result.issues()) if (stillValid(result, current, issue)) valid.add(issue);
        if (valid.isEmpty()) {
            say(checker != null && result.text().equals(current) ? "No spelling or grammar problems" : "No problems found yet");
            return;
        }
        valid.sort(Comparator.comparingInt(TextIssue::start));
        int caret = getCaretIndex();
        TextIssue found = null;
        if (direction > 0) {
            for (TextIssue issue : valid) if (issue.start() > caret) { found = issue; break; }
            if (found == null) found = valid.getFirst();
        } else {
            for (TextIssue issue : valid) if (issue.start() < caret) found = issue;
            if (found == null) found = valid.getLast();
        }
        setCaretIndex(found.start());
        say(kindWord(found) + ": " + current.substring(found.start(), found.end())
                + (found.message() == null || found.message().isBlank() ? "" : ". " + found.message())
                + ". Ctrl and period opens corrections.");
    }

    /**
     * Ctrl+period, Shift+F10 or the menu key: the correction list for the flagged word the caret is in or touches.
     */
    private void openSuggestionsAtCaret() {
        if (checker == null || isReadOnly()) return;
        TextIssue issue = issueAtIndex(getCaretIndex());
        if (issue == null) {
            say("No spelling or grammar problem here");
            return;
        }
        double[] at = getBoundsAt(issue.start());
        showSuggestions(issue, at[0], at[1] + at[2]);
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action == GLFW.GLFW_PRESS && checker != null) {
            boolean command = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
            boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
            if ((command && key == GLFW.GLFW_KEY_PERIOD) || (shift && key == GLFW.GLFW_KEY_F10) || key == GLFW.GLFW_KEY_MENU) {
                openSuggestionsAtCaret();
                return;
            }
            if (key == GLFW.GLFW_KEY_F8) {
                goToIssue(shift ? -1 : 1);
                return;
            }
        }
        super.onKeyPressed(key, action, mods);
    }

    @Override
    public String getAccessibleDescription() {
        String set = super.getAccessibleDescription();
        if (checker == null) return set;
        String hint = "F8 goes to the next spelling problem, Ctrl and period opens corrections";
        return set == null ? hint : set + ". " + hint;
    }

    /**
     * Shows the correction list for a flagged word under (x, y), if there is one.
     */
    private void openSuggestions(double x, double y) {
        TextChecker activeChecker = checker;
        PopupHost host = getPopupHost();
        if (activeChecker == null || host == null) return;

        TextIssue issue = issueAt(x, y);
        if (issue == null) return;
        showSuggestions(issue, x, y);
    }

    private void showSuggestions(TextIssue issue, double x, double y) {
        TextChecker activeChecker = checker;
        PopupHost host = getPopupHost();
        if (activeChecker == null || host == null) return;

        String current = getText();
        List<String> suggestions = activeChecker.suggest(current, issue);
        if (suggestions.size() > SuggestionPopup.MAX_ROWS)
            suggestions = suggestions.subList(0, SuggestionPopup.MAX_ROWS);

        SuggestionPopup popup = new SuggestionPopup(issue, current.substring(issue.start(), issue.end()), suggestions, activeChecker);
        float w = popup.preferredWidth();
        float h = popup.preferredHeight();
        double[] area = host.getPopupArea();
        float px = (float) Math.min(x, area[0] + area[2] - w);
        float py = (float) Math.min(y, area[1] + area[3] - h);
        popup.setBounds(Math.max(area[0], px), Math.max(area[1], py), w, h);
        host.showPopup(popup, this, popup::closeFont);
        popup.announceOpening();
    }

    /**
     * Takes the underline off every flagged copy of {@code word} now, without waiting for the next check.
     */
    private void dropIssuesOn(String word) {
        CheckResult result = checkResult;
        List<TextIssue> kept = new ArrayList<>();
        for (TextIssue issue : result.issues()) {
            if (!wordOf(result.text(), issue).equalsIgnoreCase(word)) kept.add(issue);
        }
        checkResult = new CheckResult(result.text(), kept);
    }

    private static String wordOf(String text, TextIssue issue) {
        return issue.end() <= text.length() ? text.substring(issue.start(), issue.end()) : "";
    }

    /**
     * The floating list of corrections. Like DropdownOverlay's list, a Container that
     * draws and hit-tests its own rows. Window#showPopup dismisses it on an outside
     * click or on Escape. Choosing a row replaces the misspelled word with it.
     */
    private class SuggestionPopup extends Container implements PopupKeyListener {
        static final int MAX_ROWS = 6;
        private static final float FONT_SIZE = 16f;
        private static final float ROW_HEIGHT = 28f;
        private static final float PADDING_X = 10f;

        private final TextIssue issue;
        private final String header; // the issue's message, shown above the corrections, or null
        private final String word; // the word as it was when the menu opened, which guards against the text changing underneath
        private final List<String> rows; // the corrections (or a note that there are none), then the actions
        private final int suggestionCount; // 0 when there are no corrections
        private final int firstActionRow;
        private final List<Runnable> actions = new ArrayList<>();
        private org.jetbrains.skia.Font font;
        private int active = -1; // the row the keyboard is on
        private int spokenRow = -1; // the row the pointer was last read out at
        private boolean hoverPrimed; // the first look only notes where the pointer already is, so the opening is not cut off

        SuggestionPopup(TextIssue issue, String word, List<String> suggestions, TextChecker checker) {
            super(0, 0);
            this.issue = issue;
            this.header = issue.message() == null || issue.message().isBlank() ? null : issue.message();
            this.word = word;
            this.suggestionCount = suggestions.size();
            List<String> all = new ArrayList<>(suggestions.isEmpty() ? List.of("No suggestions") : suggestions);
            this.firstActionRow = all.size();
            if (issue.kind() == TextIssue.Kind.SPELLING) {
                if (checker.canAddWords()) {
                    all.add("Add to dictionary");
                    actions.add(() -> {
                        checker.addWord(word);
                        dropIssuesOn(word);
                        checkInFull = true; // a check already under way may not know the word yet
                        lastScheduledText = null;
                    });
                }
                all.add("Ignore");
                actions.add(() -> ignoreWord(word));
            }
            this.rows = all;
            this.active = suggestionCount > 0 ? 0 : (all.size() > firstActionRow ? firstActionRow : -1);
            setCursorMode(CursorMode.POINTER);
            onMouseClick(event -> {
                int row = (int) Math.floor((event.getMouseY() - getY()) / ROW_HEIGHT) - headerRows();
                if (!isClickable(row)) return;
                choose(row);
            });
        }

        /** Replaces the word, or runs the action, then closes the list. */
        private void choose(int row) {
            PopupHost host = getPopupHost();
            if (row < suggestionCount) {
                String current = getText();
                if (issue.end() <= current.length() && current.regionMatches(issue.start(), word, 0, word.length())) {
                    replaceRange(issue.start(), issue.end(), rows.get(row));
                    say("Replaced " + word + " with " + rows.get(row));
                }
            } else {
                actions.get(row - firstActionRow).run();
                say(rows.get(row).equals("Ignore") ? "Ignoring " + word : "Added " + word + " to the dictionary");
            }
            if (host != null) host.closePopup();
        }

        /** "Misspelled: teh. 3 suggestions. the, 1 of 3": what the list is and the row the keyboard starts on. */
        void announceOpening() {
            StringBuilder text = new StringBuilder(kindWord(issue)).append(": ").append(word).append(". ");
            if (header != null) text.append(header).append(". ");
            if (suggestionCount == 0) text.append("No suggestions. ");
            else text.append(suggestionCount == 1 ? "1 suggestion. " : suggestionCount + " suggestions. ");
            if (active >= 0) text.append(rowReading(active));
            say(text.toString().strip());
        }

        private String rowReading(int row) {
            if (row < suggestionCount) return rows.get(row) + ", " + (row + 1) + " of " + suggestionCount;
            return rows.get(row);
        }

        @Override
        public void onPopupKey(int key, int mods) {
            int before = active;
            switch (key) {
                case GLFW.GLFW_KEY_DOWN -> {
                    for (int r = active + 1; r < rows.size(); r++) if (isClickable(r)) { active = r; break; }
                }
                case GLFW.GLFW_KEY_UP -> {
                    for (int r = active - 1; r >= 0; r--) if (isClickable(r)) { active = r; break; }
                }
                case GLFW.GLFW_KEY_HOME -> {
                    for (int r = 0; r < rows.size(); r++) if (isClickable(r)) { active = r; break; }
                }
                case GLFW.GLFW_KEY_END -> {
                    for (int r = rows.size() - 1; r >= 0; r--) if (isClickable(r)) { active = r; break; }
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                    if (isClickable(active)) choose(active);
                    return;
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    PopupHost host = getPopupHost();
                    if (host != null) host.closePopup();
                    say("Corrections closed");
                    return;
                }
                default -> {
                }
            }
            if (active != before && active >= 0) say(rowReading(active));
        }

        @Override
        public void update(float delta) {
            super.update(delta);
            // The mouse reading a row out as it passes over it, as a menu does.
            int hover = MouseTracker.isInWindow() && contains(MouseTracker.getX(), MouseTracker.getY())
                    ? (int) Math.floor((MouseTracker.getY() - getY()) / ROW_HEIGHT) - headerRows() : -1;
            if (!isClickable(hover)) hover = -1;
            if (!hoverPrimed) {
                hoverPrimed = true;
                spokenRow = hover;
            } else if (hover != spokenRow) {
                spokenRow = hover;
                if (hover >= 0) {
                    active = hover;
                    say(rowReading(hover));
                }
            }
        }

        private boolean isClickable(int row) {
            return row >= 0 && row < rows.size() && (row < suggestionCount || row >= firstActionRow);
        }

        private int headerRows() {
            return header == null ? 0 : 1;
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
            for (String row : rows) widest = Math.max(widest, TextShaping.width(font(), row));
            if (header != null) widest = Math.max(widest, TextShaping.width(font(), header));
            return Math.max(120f, widest + PADDING_X * 2);
        }

        float preferredHeight() {
            return (rows.size() + headerRows()) * ROW_HEIGHT;
        }

        @Override
        public void render(Renderer renderer) {
            Theme theme = ThemeManager.getCurrent();
            float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
            float radius = theme.getCornerRadius();

            renderer.drawQuad(x, y, w, h, theme.getFieldBackground(), radius);

            renderer.pushClip(x, y, w, h);
            int hoverRow = MouseTracker.isInWindow() && contains(MouseTracker.getX(), MouseTracker.getY())
                    ? (int) Math.floor((MouseTracker.getY() - y) / ROW_HEIGHT) - headerRows() : -1;
            if (!isClickable(hoverRow)) hoverRow = active; // the keyboard's row looks like a hovered one
            org.jetbrains.skia.Font f = font();
            float textHeight = FontMetricsCompat.of(f).getHeight();
            if (header != null) {
                renderer.drawText(header, x + PADDING_X, y + (ROW_HEIGHT - textHeight) / 2f, f, theme.getText().withOpacity(0.6f));
            }
            for (int i = 0; i < rows.size(); i++) {
                float rowY = y + (i + headerRows()) * ROW_HEIGHT;
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
                CheckResult previous = checkResult;
                List<TextIssue> issues = new ArrayList<>(checkInFull
                        ? activeChecker.check(text)
                        : TextChecker.checkChanged(activeChecker, previous.text(), previous.issues(), text));
                checkInFull = false;
                issues.removeIf(issue -> ignoredWords.contains(wordOf(text, issue).toLowerCase(Locale.ROOT)));
                checkResult = new CheckResult(text, issues);
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
        FontMetricsCompat metrics = FontMetricsCompat.of(font);
        float underlineY = y - metrics.getAscent() + metrics.getDescent() * 0.6f;

        for (TextIssue issue : result.issues()) {
            if (issue.end() <= lineStart || issue.start() >= lineEnd) continue;
            // Results lag edits by the debounce, so only draw an issue whose word is still exactly what was checked.
            int len = issue.end() - issue.start();
            if (issue.end() > current.length() || !current.regionMatches(issue.start(), result.text(), issue.start(), len))
                continue;

            int start = Math.max(issue.start(), lineStart);
            int end = Math.min(issue.end(), lineEnd);
            float x0 = x + TextShaping.width(font, lineText.substring(0, start - lineStart));
            float x1 = x + TextShaping.width(font, lineText.substring(0, end - lineStart));
            drawSquiggle(renderer, x0, x1, underlineY, issue.kind() == TextIssue.Kind.GRAMMAR ? grammarColor : spellingColor);
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

        // A line with anything that needs shaping was measured whole by the shaper, which comes out narrower than plain
        // text. Drawn in plain pieces, the Latin ones would add up wider than that and run past the edge, so every
        // piece is shaped too and put where the whole line has it. Right to left lines keep going piece by piece.
        boolean shapedLine = TextShaping.needsShaping(lineText) && !TextShaping.isRightToLeft(lineText);
        try (TextShaping.Scope ignored = shapedLine ? TextShaping.shapeAll() : null) {
            drawRuns(renderer, lineText, lineStart, x, y, font, shapedLine);
        }
    }

    private void drawRuns(Renderer renderer, String lineText, int lineStart, float x, float y, org.jetbrains.skia.Font font, boolean shapedLine) {
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
                cursorX = drawRun(renderer, lineText, pos - lineStart, start - lineStart, x, cursorX, y, font, defaultColor, shapedLine);
            }

            Paint color = span.color() != null ? span.color() : defaultColor;
            cursorX = drawRun(renderer, lineText, start - lineStart, end - lineStart, x, cursorX, y, font, color, shapedLine);
            pos = end;
        }

        if (pos < lineEnd) {
            drawRun(renderer, lineText, pos - lineStart, lineText.length(), x, cursorX, y, font, defaultColor, shapedLine);
        }
    }

    /**
     * Draws {@code run} at {@code x} (a no-op if empty or {@code color} is null, matching every other draw-if-non-null convention in this engine) and returns the x position right after it, for the next run to continue from.
     */
    private float drawRun(Renderer renderer, String lineText, int from, int to, float lineX, float x, float y, org.jetbrains.skia.Font font, Paint color, boolean shapedLine) {
        if (from >= to || color == null) return x;
        String run = lineText.substring(from, to);
        // On a shaped line the run starts where the whole line puts that character, so kerning across runs isn't lost.
        float runX = shapedLine ? lineX + TextShaping.xAt(font, lineText, from) : x;
        renderer.drawText(run, runX, y, font, color);
        return runX + TextShaping.width(font, run);
    }
}
