package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A scrolling log of text with a command line under it: a game's developer console, a chat window, a build log.
 * <pre>{@code
 * ConsoleLog console = new ConsoleLog(520, 260);
 * console.onCommand(line -> {
 *     console.info("Ran " + line);
 *     run(line);
 * });
 * console.setCompleter(prefix -> commands.stream().filter(c -> c.startsWith(prefix)).toList());
 * console.warn("Low on memory");
 * console.chat("Ana", "anyone seen the key?", Color.CYAN);
 * }</pre>
 * Lines have a {@linkplain Level level} that sets their color ({@link #info}, {@link #warn}, {@link #error}, {@link #debug},
 * or {@link #println} for plain text), and a chat line has a colored sender ({@link #chat}). Long lines wrap to the width,
 * and the oldest lines are dropped past {@link #setMaxLines}. The log sticks to the newest line, and the wheel, Page Up and
 * Page Down scroll back through it; while scrolled back, new lines do not pull the view away. End, or {@link
 * #scrollToBottom}, returns to the newest.
 * <p>
 * The command line is a text field. Enter hands the text to {@link #onCommand} (and echoes it, {@link #setEchoCommands});
 * Up and Down walk through the commands already entered, and Tab completes with {@link #setCompleter}: one match fills it
 * in, several fill in what they share and list the matches. {@link #setInputVisible} hides the field to make a read-only log.
 * <p>
 * It can be called from any thread: lines are added under a lock and drawn on the render thread. It follows the theme like a
 * container that {@link #useTheme() uses the theme}; the text color and accent come from the theme, and the warning and error
 * colors can be set with {@link #setLevelColor}.
 */
public class ConsoleLog extends Container implements Scrollable, FontScalable {
    /**
     * How serious a line is, which sets its color and what {@link #setMinLevel} hides.
     */
    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    private static final DateTimeFormatter DEFAULT_STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_HISTORY = 100;

    private static final class Line {
        final Level level;
        final long millis;
        final String sender; // empty for none
        final Paint senderPaint;
        final Paint textPaint; // null for the level's color
        final String text;
        // Filled by layout, for one width.
        List<String> rows = List.of();
        String stamp = "";
        float indent;
        float layoutWidth = -1f;
        int layoutVersion = -1;

        Line(Level level, long millis, String sender, Paint senderPaint, String text, Paint textPaint) {
            this.level = level;
            this.millis = millis;
            this.sender = sender;
            this.senderPaint = senderPaint;
            this.text = text;
            this.textPaint = textPaint;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private final Object lock = new Object();
    private int maxLines = 1000;
    private Level minLevel = Level.DEBUG;

    private boolean showTimestamps;
    private DateTimeFormatter stampFormat = DEFAULT_STAMP;
    private boolean echoCommands = true;
    private int layoutVersion; // bumped when something that changes the wrapping changes

    private float fontSize = 14f;
    private final Typeface typeface = Font.getDefault();
    private org.jetbrains.skia.Font font;
    private boolean fontDirty = true;
    private float padding = 6f;

    private final Overridable<Paint> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Paint> accentColor = new Overridable<>(Color.GRAY);
    private final Map<Level, Paint> levelColors = new EnumMap<>(Level.class);

    private float scrollFromBottom; // points scrolled back from the newest line; 0 follows it
    private float lastTotal; // the height of the lines at the last draw, to tell how much was added
    private boolean inputVisible = true;
    private final ConsoleInput input;
    private float inputHeight = 30f;

    private final Deque<String> history = new ArrayDeque<>(); // newest first
    private int historyPos = -1;
    private String draft = "";
    private Consumer<String> onCommand;
    private Function<String, List<String>> completer;

    public ConsoleLog(double width, double height) {
        super(width, height);
        levelColors.put(Level.WARN, new Color(0.95f, 0.72f, 0.2f, 1f));
        levelColors.put(Level.ERROR, new Color(0.92f, 0.33f, 0.33f, 1f));
        input = new ConsoleInput(width, inputHeight);
        input.setPlaceholder("Type a command...");
        input.onSubmit(this::submit);
        addElement(input);
        layoutInput();
        onMouseClick(event -> focusInput());
        useTheme();
        ThemeManager.register(this);
    }

    /* Writing */

    /**
     * Adds a plain line, in the text color.
     */
    public void println(String text) {
        add(Level.INFO, "", null, text, null);
    }

    /**
     * Adds a line in {@code color}.
     */
    public void print(String text, Paint color) {
        add(Level.INFO, "", null, text, color);
    }

    public void debug(String text) {
        add(Level.DEBUG, "", null, text, null);
    }

    public void info(String text) {
        add(Level.INFO, "", null, text, null);
    }

    public void warn(String text) {
        add(Level.WARN, "", null, text, null);
    }

    public void error(String text) {
        add(Level.ERROR, "", null, text, null);
    }

    /**
     * Adds a line as {@code sender: message}, the sender in {@code senderColor} (the accent when null).
     */
    public void chat(String sender, String message, Paint senderColor) {
        add(Level.INFO, sender == null ? "" : sender, senderColor, message, null);
    }

    private void add(Level level, String sender, Paint senderPaint, String text, Paint textPaint) {
        Line line = new Line(level, System.currentTimeMillis(), sender, senderPaint, text == null ? "" : text, textPaint);
        synchronized (lock) {
            lines.add(line);
            while (lines.size() > maxLines) lines.removeFirst();
        }
    }

    public void clear() {
        synchronized (lock) {
            lines.clear();
            scrollFromBottom = 0f;
            lastTotal = 0f;
        }
    }

    /**
     * How many lines are kept, shown or not.
     */
    public int getLineCount() {
        synchronized (lock) {
            return lines.size();
        }
    }

    /**
     * The text of every line kept, oldest first, one per line, with timestamps and senders if they are shown. For copying the
     * log out or testing.
     */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        synchronized (lock) {
            for (Line line : lines) {
                if (sb.length() > 0) sb.append('\n');
                if (showTimestamps) sb.append(stampOf(line)).append(' ');
                if (!line.sender.isEmpty()) sb.append(line.sender).append(": ");
                sb.append(line.text);
            }
        }
        return sb.toString();
    }

    public int getMaxLines() {
        return maxLines;
    }

    /**
     * The most lines kept; the oldest go first. 1000 by default.
     */
    public void setMaxLines(int maxLines) {
        this.maxLines = Math.max(1, maxLines);
        synchronized (lock) {
            while (lines.size() > this.maxLines) lines.removeFirst();
        }
    }

    public Level getMinLevel() {
        return minLevel;
    }

    /**
     * Hides the lines less serious than {@code level}; the default {@link Level#DEBUG} shows everything. Hidden lines are
     * kept and come back when this is lowered.
     */
    public void setMinLevel(Level level) {
        this.minLevel = level == null ? Level.DEBUG : level;
    }

    public boolean isShowTimestamps() {
        return showTimestamps;
    }

    /**
     * Whether each line starts with the time it was added. Off by default.
     */
    public void setShowTimestamps(boolean showTimestamps) {
        this.showTimestamps = showTimestamps;
        layoutVersion++;
    }

    /**
     * How timestamps are written; {@code HH:mm:ss} by default.
     */
    public void setTimestampFormat(DateTimeFormatter format) {
        this.stampFormat = format == null ? DEFAULT_STAMP : format;
        layoutVersion++;
    }

    /**
     * The color of a level's lines: yellowish for {@link Level#WARN}, red for {@link Level#ERROR}; the others follow the
     * theme's text color (debug dimmed) until set.
     */
    public void setLevelColor(Level level, Paint paint) {
        if (paint == null) levelColors.remove(level);
        else levelColors.put(level, paint);
    }

    /* Scrolling */

    /**
     * Scrolls to the newest line and follows new ones again.
     */
    public void scrollToBottom() {
        scrollFromBottom = 0f;
    }

    public boolean isAtBottom() {
        return scrollFromBottom <= 0f;
    }

    @Override
    public void onScroll(double deltaX, double deltaY) {
        scrollBy((float) deltaY * rowHeight() * 3f);
    }

    private void scrollBy(float up) {
        scrollFromBottom = Math.max(0f, Math.min(maxScroll(), scrollFromBottom + up));
    }

    private float maxScroll() {
        return Math.max(0f, contentHeight() - outputHeight());
    }

    private float outputHeight() {
        return (float) getHeight() - (inputVisible ? inputHeight : 0f) - padding * 2f;
    }

    private float rowHeight() {
        FontMetricsCompat m = FontMetricsCompat.of(getFont());
        return -m.getAscent() + m.getDescent() + 2f;
    }

    /* Command line */

    public boolean isInputVisible() {
        return inputVisible;
    }

    /**
     * Whether the command line is shown. On by default; off makes a read-only log.
     */
    public void setInputVisible(boolean inputVisible) {
        this.inputVisible = inputVisible;
        input.setEnabled(inputVisible);
        layoutInput();
    }

    /**
     * Fired with the trimmed text when the user presses Enter on a non-empty command line.
     */
    public void onCommand(Consumer<String> onCommand) {
        this.onCommand = onCommand;
    }

    public boolean isEchoCommands() {
        return echoCommands;
    }

    /**
     * Whether an entered command is written to the log as {@code > command}, in the accent color. On by default.
     */
    public void setEchoCommands(boolean echoCommands) {
        this.echoCommands = echoCommands;
    }

    /**
     * Gives Tab its completions: it is called with the text in the command line and returns the strings it could become, or
     * none. Null, the default, leaves Tab doing nothing.
     */
    public void setCompleter(Function<String, List<String>> completer) {
        this.completer = completer;
    }

    public void setPlaceholder(String placeholder) {
        input.setPlaceholder(placeholder);
    }

    /**
     * The command line, for setting its text or focusing it.
     */
    public TextFieldOverlay getInput() {
        return input;
    }

    /**
     * Gives the keyboard to the command line.
     */
    public void focusInput() {
        PopupHost host = getPopupHost();
        if (inputVisible && host != null) host.requestFocus(input);
    }

    /**
     * The commands entered, newest first.
     */
    public List<String> getHistory() {
        return List.copyOf(history);
    }

    private void submit(String raw) {
        String command = raw == null ? "" : raw.trim();
        input.setText("");
        historyPos = -1;
        draft = "";
        scrollFromBottom = 0f;
        if (command.isEmpty()) return;
        if (history.isEmpty() || !history.peekFirst().equals(command)) {
            history.addFirst(command);
            while (history.size() > MAX_HISTORY) history.removeLast();
        }
        if (echoCommands) add(Level.INFO, "", null, "> " + command, accentColor.get());
        if (onCommand != null) onCommand.accept(command);
    }

    /**
     * Steps through the commands entered: back to older ones, or forward to newer ones and finally the unsent text that was
     * in the field before the first step back.
     */
    private void walkHistory(boolean older) {
        if (history.isEmpty()) return;
        if (historyPos == -1) {
            if (!older) return; // already at the draft
            draft = input.getText();
        }
        int next = older ? Math.min(historyPos + 1, history.size() - 1) : historyPos - 1;
        historyPos = next;
        input.setText(next == -1 ? draft : new ArrayList<>(history).get(next));
    }

    private void complete() {
        if (completer == null) return;
        String typed = input.getText();
        List<String> options = completer.apply(typed);
        if (options == null || options.isEmpty()) return;
        String common = options.getFirst();
        for (String option : options) {
            int n = 0;
            while (n < common.length() && n < option.length() && common.charAt(n) == option.charAt(n)) n++;
            common = common.substring(0, n);
        }
        if (options.size() == 1) {
            input.setText(options.getFirst());
        } else {
            if (common.length() > typed.length()) input.setText(common);
            add(Level.INFO, "", null, String.join("   ", options), null);
        }
    }

    /**
     * The command line: a text field that also reads the history and completion keys, and scrolls the log with Page Up and
     * Page Down.
     */
    private final class ConsoleInput extends TextFieldOverlay {
        ConsoleInput(double width, double height) {
            super(width, height);
        }

        @Override
        public boolean wantsTab() {
            return completer != null;
        }

        @Override
        public void onKeyPressed(int key, int action, int mods) {
            if (action != GLFW.GLFW_RELEASE) {
                switch (key) {
                    case GLFW.GLFW_KEY_UP -> {
                        walkHistory(true);
                        return;
                    }
                    case GLFW.GLFW_KEY_DOWN -> {
                        walkHistory(false);
                        return;
                    }
                    case GLFW.GLFW_KEY_TAB -> {
                        complete();
                        return;
                    }
                    case GLFW.GLFW_KEY_PAGE_UP -> {
                        scrollBy(outputHeight() * 0.9f);
                        return;
                    }
                    case GLFW.GLFW_KEY_PAGE_DOWN -> {
                        scrollBy(-outputHeight() * 0.9f);
                        return;
                    }
                    case GLFW.GLFW_KEY_END -> {
                        if ((mods & GLFW.GLFW_MOD_CONTROL) != 0) {
                            scrollToBottom();
                            return;
                        }
                    }
                    default -> {
                    }
                }
            }
            super.onKeyPressed(key, action, mods);
        }
    }

    /* Layout */

    @Override
    public void setWidth(double width) {
        super.setWidth(width);
        layoutInput();
    }

    @Override
    public void setHeight(double height) {
        super.setHeight(height);
        layoutInput();
    }

    private void layoutInput() {
        if (input == null) return; // the constructor of Container sets the size before the field exists
        input.setWidth(Math.max(0, getWidth()));
        input.setHeight(inputHeight);
        input.setPosition(0, Math.max(0, getHeight() - inputHeight));
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        fontDirty = true;
        layoutVersion++;
    }

    @Override
    public float[] getFontSizes() {
        return new float[]{fontSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        setFontSize(sizes[0]);
    }

    public Paint getTextColor() {
        return textColor.get();
    }

    public void setTextColor(Paint textColor) {
        this.textColor.set(textColor);
    }

    public Paint getAccentColor() {
        return accentColor.get();
    }

    public void setAccentColor(Paint accentColor) {
        this.accentColor.set(accentColor);
    }

    /* Theme */

    @Override
    public void applyTheme(Theme theme) {
        super.applyTheme(theme);
        textColor.applyTheme(theme.getText());
        accentColor.applyTheme(theme.getAccent());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        textColor.clearExplicit();
        accentColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(accentColor.get(), delta);
    }

    /* Wrapping and drawing */

    private org.jetbrains.skia.Font getFont() {
        if (fontDirty || font == null) {
            if (font != null) font.close();
            font = new org.jetbrains.skia.Font(typeface, fontSize);
            fontDirty = false;
        }
        return font;
    }

    private String stampOf(Line line) {
        return stampFormat.format(Instant.ofEpochMilli(line.millis).atZone(ZoneId.systemDefault()));
    }

    private boolean shown(Line line) {
        return line.level.ordinal() >= minLevel.ordinal();
    }

    /**
     * Wraps a line for the current width, once per width. Skia's font measuring is the cost, so a line already laid out for
     * this width and version is left alone.
     */
    private void layout(Line line, float contentWidth) {
        if (line.layoutWidth == contentWidth && line.layoutVersion == layoutVersion) return;
        org.jetbrains.skia.Font f = getFont();
        line.stamp = showTimestamps ? stampOf(line) + " " : "";
        String sender = line.sender.isEmpty() ? "" : line.sender + ": ";
        line.indent = f.measureTextWidth(line.stamp) + f.measureTextWidth(sender);
        line.rows = wrap(line.text, f, Math.max(20f, contentWidth - line.indent));
        line.layoutWidth = contentWidth;
        line.layoutVersion = layoutVersion;
    }

    private float contentWidth0() {
        return (float) getWidth() - padding * 2f - 8f;
    }

    private float contentHeight() {
        float h = 0f, row = rowHeight(), width = contentWidth0();
        synchronized (lock) {
            for (Line line : lines) {
                if (!shown(line)) continue;
                layout(line, width);
                h += row * line.rows.size();
            }
        }
        return h;
    }

    /**
     * Greedy word wrap of {@code text} to {@code maxWidth}, honoring newlines and breaking a word longer than a row by
     * characters. Always at least one row.
     */
    static List<String> wrap(String text, org.jetbrains.skia.Font f, float maxWidth) {
        List<String> rows = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder row = new StringBuilder();
            for (String word : paragraph.split(" ", -1)) {
                String candidate = row.isEmpty() ? word : row + " " + word;
                if (f.measureTextWidth(candidate) <= maxWidth) {
                    row.setLength(0);
                    row.append(candidate);
                    continue;
                }
                if (!row.isEmpty()) {
                    rows.add(row.toString());
                    row.setLength(0);
                }
                // A word wider than the row is cut where it no longer fits.
                String rest = word;
                while (f.measureTextWidth(rest) > maxWidth && rest.length() > 1) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && f.measureTextWidth(rest.substring(0, cut)) > maxWidth) cut--;
                    rows.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                row.append(rest);
            }
            rows.add(row.toString());
        }
        return rows;
    }

    /**
     * How many rows of text are laid out at the current width (visible lines only). For tests and for sizing.
     */
    public int getRowCount() {
        int n = 0;
        float width = contentWidth0();
        synchronized (lock) {
            for (Line line : lines) {
                if (!shown(line)) continue;
                layout(line, width);
                n += line.rows.size();
            }
        }
        return n;
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        renderBackground(renderer);
        boolean clip = isClipping();
        if (clip) pushChildClip(renderer);
        drawLines(renderer);
        renderChildren(renderer);
        if (clip) renderer.popClip();
    }

    private void drawLines(Renderer renderer) {
        float x = (float) getX() + padding, areaTop = (float) getY() + padding, areaH = outputHeight();
        float width = contentWidth0(), row = rowHeight();
        org.jetbrains.skia.Font f = getFont();
        float total = contentHeight();
        // Scrolled back: keep the same text under the eye as the log grows.
        if (scrollFromBottom > 0f && total > lastTotal) scrollFromBottom += total - lastTotal;
        lastTotal = total;
        scrollFromBottom = Math.max(0f, Math.min(scrollFromBottom, Math.max(0f, total - areaH)));
        // The newest row sits on the bottom of the area when following; content shorter than the area hangs from the top.
        float y = total <= areaH ? areaTop : areaTop + areaH - total + scrollFromBottom;
        Paint base = resolveBase();
        renderer.pushClip(x - padding, areaTop, (float) getWidth(), areaH);
        synchronized (lock) {
            for (Line line : lines) {
                if (!shown(line)) continue;
                layout(line, width);
                float lineH = row * line.rows.size();
                if (y + lineH >= areaTop && y <= areaTop + areaH) drawLine(renderer, line, x, y, row, f, base);
                y += lineH;
            }
        }
        renderer.popClip();
        if (total > areaH) drawScrollBar(renderer, areaTop, areaH, total);
    }

    private Paint resolveBase() {
        return AnimatedPaint.resolve(textColor.get());
    }

    private void drawLine(Renderer renderer, Line line, float x, float y, float row, org.jetbrains.skia.Font f, Paint base) {
        Paint color = line.textPaint != null ? line.textPaint : levelColors.getOrDefault(line.level, base);
        float textY = y + 1f;
        float cursor = x;
        if (!line.stamp.isEmpty()) {
            renderer.pushAlpha(0.5f);
            renderer.drawText(line.stamp, cursor, textY, f, base);
            renderer.popAlpha();
            cursor += f.measureTextWidth(line.stamp);
        }
        if (!line.sender.isEmpty()) {
            renderer.drawText(line.sender + ": ", cursor, textY, f, line.senderPaint != null ? line.senderPaint : accentColor.get());
        }
        boolean dim = line.level == Level.DEBUG && line.textPaint == null && !levelColors.containsKey(Level.DEBUG);
        if (dim) renderer.pushAlpha(0.6f);
        for (int i = 0; i < line.rows.size(); i++) {
            renderer.drawText(line.rows.get(i), x + line.indent, textY + i * row, f, color);
        }
        if (dim) renderer.popAlpha();
    }

    private void drawScrollBar(Renderer renderer, float top, float height, float total) {
        float thumb = Math.max(18f, height * height / total);
        float max = total - height;
        float ratio = max <= 0 ? 1f : 1f - scrollFromBottom / max; // 1 is the bottom
        float thumbY = top + (height - thumb) * ratio;
        renderer.pushAlpha(0.35f);
        renderer.drawQuad((float) (getX() + getWidth()) - 6f, thumbY, 3f, thumb, textColor.get(), 1.5f);
        renderer.popAlpha();
    }

    /**
     * Frees the font used for the log, if one was drawn. The command line frees its own.
     */
    public void dispose() {
        if (font != null) {
            font.close();
            font = null;
        }
        input.dispose();
    }
}
