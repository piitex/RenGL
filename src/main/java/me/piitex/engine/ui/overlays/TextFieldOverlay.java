package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.Clipboard;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.TextEditingOptions;
import me.piitex.engine.ui.text.TextUndoHistory;
import me.piitex.engine.ui.text.TextUndoHistory.EditKind;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.FontMetrics;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.function.Consumer;

/**
 * A single-line editable text box.
 * <p>
 * Unlike {@link TextOverlay}, which just draws a string, this needs an actual bounds
 * box (hence the {@code width}/{@code height} constructor params, like
 * {@link Container}) since it clips its content to that box and
 * scrolls the text horizontally once it overflows.
 * <p>
 * Text input in this engine comes from two different GLFW callbacks, wired through
 * {@link me.piitex.engine.input.InputManager}/{@link me.piitex.engine.input.InputProcessor}:
 * GLFW's char callback delivers actual typed Unicode codepoints (already resolved
 * against keyboard layout/shift/dead keys) via {@link #onCharTyped}, while the key
 * callback delivers raw, layout-independent key codes through {@link #onKeyPressed},
 * which is used here only for the control keys that never arrive as characters:
 * backspace, delete, the arrows, home, end, and enter. Both are forwarded here only
 * while this field holds keyboard focus, which {@code InputProcessor} grants on click
 * because this class implements {@link Focusable}.
 * <p>
 * Mouse-driven selection, meaning click-drag and double-click-to-select-word, comes
 * through {@link Draggable} rather than {@link #onMouseClick}, which leaves
 * {@code onMouseClick(...)} free for the application to use without disturbing text
 * selection.
 * <p>
 * Text color, placeholder, selection and caret styling, and the copy, paste, and
 * selection toggles all live in a shared {@link TextEditingOptions}
 * ({@link #getOptions()}). That class, not this one, is where those settings are meant
 * to be built once and reused across this and any other text-editing overlay.
 */
public class TextFieldOverlay extends Overlay implements Focusable, Draggable, Themeable, FontScalable {
    private static final float BLINK_INTERVAL_SECONDS = 0.5f;
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final double DOUBLE_CLICK_MAX_DISTANCE = 6.0;
    /**
     * Minimum mouse travel, in window pixels, from the press point before a hold-and-move counts as an actual drag rather than the jitter an ordinary click's press/release almost always contains.
     */
    private static final double DRAG_THRESHOLD_PIXELS = 4.0;

    private final StringBuilder text = new StringBuilder();
    private boolean password = false;
    private final TextUndoHistory history = new TextUndoHistory();
    private int cursorIndex = 0;
    /**
     * The other edge of the selection, or -1 if nothing is selected. {@link #cursorIndex} is always the active edge.
     */
    private int selectionAnchor = -1;

    private TextEditingOptions options = new TextEditingOptions();

    private float fontSize;
    private float paddingX = 8f;
    private String prefix = "";
    private boolean rightAligned;

    private Typeface typeface;
    private org.jetbrains.skia.Font skiaFont;
    private boolean fontDirty = true;

    private boolean focused = false;
    private boolean cursorVisible = false;
    private float blinkTimer = 0f;
    private float scrollOffset = 0f;

    /**
     * Consecutive presses landing within the double-click time and distance of each other: 1 for a fresh click, 2 for a double, 3 or more for a triple.
     */
    private int clickCount = 0;
    private long lastPressTimeNanos = Long.MIN_VALUE;
    private double lastPressX, lastPressY;
    /**
     * Where the current press started, or -1 between drags. See {@link #onDragStart}. It is promoted to {@link #selectionAnchor} only once the mouse actually moves.
     */
    private int dragAnchorIndex = -1;

    private Consumer<String> onTextChangedConsumer;
    private Consumer<String> onSubmitConsumer;
    private Consumer<Boolean> onFocusChangedConsumer;

    public TextFieldOverlay(double width, double height) {
        this(0, 0, width, height, 16f, Color.BLACK);
    }

    /**
     * An empty field with a 16pt black {@link Font#getDefault() default} font.
     */
    public TextFieldOverlay(double x, double y, double width, double height) {
        this(x, y, width, height, 16f, Color.BLACK);
    }

    /**
     * @param textColor a plain {@link Color} for a solid fill, or a
     *                  {@link me.piitex.engine.ui.color.LinearGradient}/
     *                  {@link me.piitex.engine.ui.color.ScrollingGradient} for a
     *                  gradient or animated fill. This is immediately overwritten by
     *                  the current {@link me.piitex.engine.ui.theme.Theme}'s text
     *                  color (see {@link #applyTheme}), and again on every future
     *                  theme switch, so it only sticks if you re-apply it with
     *                  {@link #setTextColor} afterward.
     */
    public TextFieldOverlay(double x, double y, double width, double height, float fontSize, Paint textColor) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);
        this.fontSize = fontSize;
        // Seeded as a baseline, not pinned. Pinning it would silently block applyTheme's
        // own write, which happens moments later through ThemeManager.register below.
        // See Overridable.
        options.applyThemeTextColor(textColor);
        this.typeface = Font.getDefault();

        setCursorMode(CursorMode.TEXT);
        setFocusTraversable(true); // Tab traversal is on by default for this overlay; see Element#isFocusTraversable
        ThemeManager.register(this);
    }

    /**
     * Uses a custom font file (cached via {@link Font}) instead of the system default.
     */
    public TextFieldOverlay(double x, double y, double width, double height, File fontFile, float fontSize, Paint textColor) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);
        this.fontSize = fontSize;
        options.applyThemeTextColor(textColor); // see the other constructor
        this.typeface = Font.load(fontFile);

        setCursorMode(CursorMode.TEXT);
        setFocusTraversable(true); // Tab traversal is on by default for this overlay; see Element#isFocusTraversable
        ThemeManager.register(this);
    }

    /**
     * Applies {@code theme}'s field tokens to this field's box (background, hover,
     * border, corner radius) and to the typed text/caret/placeholder/selection colors
     * in {@link #getOptions()}, as a baseline. Each property below takes effect only
     * while it has not been set explicitly through {@link #getStyling()} or
     * {@link TextEditingOptions}'s own setters. See
     * {@link me.piitex.engine.ui.theme.Overridable} for the exact rule, and
     * {@link Themeable} for when this runs.
     */
    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getFieldBackground());
        getStyling().applyThemeHoverColor(theme.getFieldHoverBackground());
        getStyling().applyThemeBorderColor(theme.getFieldBorder());
        getStyling().applyThemeBorderThickness(theme.getBorderThickness());
        getStyling().applyThemeCornerRadius(theme.getCornerRadius());
        options.applyThemeTextColor(theme.getText());
        options.applyThemePlaceholderColor(theme.getPlaceholderText());
        options.applyThemeCaretColor(theme.getCaret());
        options.applyThemeSelectionColor(theme.getSelection());
    }

    /**
     * Use a custom font file instead of the system default. Cached by path, so it is cheap to call repeatedly with the same file.
     */
    public void setFontFile(File fontFile) {
        this.typeface = Font.load(fontFile);
        fontDirty = true;
    }

    public void setFontFile(String fontFilePath) {
        setFontFile(new File(fontFilePath));
    }

    /**
     * The current field contents.
     */
    public String getText() {
        return text.toString();
    }

    /**
     * Replaces the field contents wholesale and moves the cursor to the end. Does not fire {@link #onTextChanged}. Works on a {@link #isReadOnly() read-only} field, and discards the undo history.
     */
    public void setText(String newText) {
        history.clear();
        text.setLength(0);
        if (newText != null) {
            text.append(newText);
        }
        cursorIndex = text.length();
        clearSelection();
        scrollOffset = 0f;
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        fontDirty = true;
    }

    @Override
    public float[] getFontSizes() {
        return new float[]{fontSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        setFontSize(sizes[0]);
    }

    /**
     * The shared styling and behavior settings: text, placeholder, caret, and selection
     * color, caret shape and size, and the copy, paste, and selection toggles. See
     * {@link TextEditingOptions}. Never null; mutate the returned instance directly, or
     * replace it wholesale with {@link #setOptions} to share one instance across several
     * text overlays.
     */
    public TextEditingOptions getOptions() {
        return options;
    }

    /**
     * Replaces this field's options wholesale. Pass the same instance to multiple text overlays to keep them themed identically. Passing null is ignored.
     */
    public void setOptions(TextEditingOptions options) {
        if (options != null) {
            this.options = options;
        }
    }

    public Paint getTextColor() {
        return options.getTextColor();
    }

    public void setTextColor(Paint textColor) {
        options.setTextColor(textColor);
    }

    /**
     * Text shown (in {@link #getPlaceholderColor()}) whenever the field is empty.
     */
    public String getPlaceholder() {
        return options.getPlaceholder();
    }

    public void setPlaceholder(String placeholder) {
        options.setPlaceholder(placeholder);
    }

    public Paint getPlaceholderColor() {
        return options.getPlaceholderColor();
    }

    public void setPlaceholderColor(Paint placeholderColor) {
        options.setPlaceholderColor(placeholderColor);
    }

    public Color getCaretColor() {
        return options.getCaretColor();
    }

    public void setCaretColor(Color caretColor) {
        options.setCaretColor(caretColor);
    }

    public Color getSelectionColor() {
        return options.getSelectionColor();
    }

    public void setSelectionColor(Color selectionColor) {
        options.setSelectionColor(selectionColor);
    }

    /**
     * Whether any text is currently selected.
     */
    public boolean hasSelection() {
        return selectionAnchor >= 0 && selectionAnchor != cursorIndex;
    }

    /**
     * Clears any active selection without changing the cursor position.
     */
    public void clearSelection() {
        selectionAnchor = -1;
    }

    private int selectionStart() {
        return Math.min(selectionAnchor, cursorIndex);
    }

    private int selectionEnd() {
        return Math.max(selectionAnchor, cursorIndex);
    }

    /**
     * Deletes the current selection, if any, and collapses the cursor to where it started.
     */
    private void deleteSelection() {
        int start = selectionStart();
        int end = selectionEnd();
        text.delete(start, end);
        cursorIndex = start;
        clearSelection();
    }

    /**
     * The currently selected text, or "" if nothing is selected.
     */
    public String getSelectedText() {
        return hasSelection() ? text.substring(selectionStart(), selectionEnd()) : "";
    }

    /**
     * Inserts {@code insert} at the cursor, replacing the active selection first if
     * there is one, and truncates it to whatever room {@link #getMaxLength()} still
     * allows rather than rejecting it outright. This is used for both a single typed
     * character and a whole pasted string. It is a no-op, and fires no
     * {@link #onTextChanged}, if {@code insert} is empty or there is no room left.
     */
    private void insertText(String insert) {
        if (options.isReadOnly() || insert.isEmpty()) return;

        int maxLength = options.getMaxLength();
        if (maxLength >= 0) {
            int occupied = text.codePointCount(0, text.length());
            if (hasSelection()) {
                occupied -= text.codePointCount(selectionStart(), selectionEnd());
            }
            int room = maxLength - occupied;
            if (room <= 0) return;
            insert = truncateToCodepoints(insert, room);
            if (insert.isEmpty()) return;
        }

        // A lone typed character with nothing selected is what coalesces into one undo step; a paste or an overwrite of a selection stands alone.
        boolean plainTyping = !hasSelection() && insert.codePointCount(0, insert.length()) == 1;
        recordEdit(plainTyping ? EditKind.TYPING : EditKind.OTHER);

        if (hasSelection()) {
            deleteSelection();
        } else {
            // Scrub any anchor left over from elsewhere (e.g. a drag that armed one but
            // never crossed the selection threshold) before moving the cursor below.
            // An anchor equal to the pre-insert cursorIndex would otherwise fall behind
            // it and read as a selection on the next call.
            clearSelection();
        }

        text.insert(cursorIndex, insert);
        cursorIndex += insert.length();
        fireTextChanged();
    }

    private TextUndoHistory.Snapshot snapshot() {
        return new TextUndoHistory.Snapshot(text.toString(), cursorIndex, selectionAnchor);
    }

    /**
     * Must be called just before every user edit mutates {@link #text}.
     */
    private void recordEdit(EditKind kind) {
        if (!options.isUndoEnabled()) {
            history.clear();
            return;
        }
        history.setLimit(options.getUndoLimit());
        history.record(snapshot(), kind);
    }

    private void restore(TextUndoHistory.Snapshot state) {
        text.setLength(0);
        text.append(state.text());
        cursorIndex = Math.min(state.cursorIndex(), text.length());
        selectionAnchor = state.selectionAnchor() > text.length() ? -1 : state.selectionAnchor();
        fireTextChanged();
        resetBlink();
    }

    private void undo() {
        if (options.isReadOnly() || !options.isUndoEnabled()) return;
        TextUndoHistory.Snapshot previous = history.undo(snapshot());
        if (previous != null) restore(previous);
    }

    private void redo() {
        if (options.isReadOnly() || !options.isUndoEnabled()) return;
        TextUndoHistory.Snapshot next = history.redo(snapshot());
        if (next != null) restore(next);
    }

    private static String truncateToCodepoints(String s, int maxCodepoints) {
        if (s.codePointCount(0, s.length()) <= maxCodepoints) return s;
        return s.substring(0, s.offsetByCodePoints(0, maxCodepoints));
    }

    /**
     * Collapses newlines/tabs to spaces and drops other control characters, since this is a single-line field but a pasted clipboard string might contain either.
     */
    private static String sanitizeForSingleLine(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                sb.append(' ');
            } else if (!Character.isISOControl(cp)) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    /**
     * Maximum number of codepoints allowed, or -1 (the default) for unlimited.
     */
    public int getMaxLength() {
        return options.getMaxLength();
    }

    public void setMaxLength(int maxLength) {
        options.setMaxLength(maxLength);
    }

    /**
     * Horizontal inset, in logical points, between the field's edges and its text/cursor.
     */
    public float getPadding() {
        return paddingX;
    }

    public void setPadding(float paddingX) {
        this.paddingX = paddingX;
    }

    public boolean isFocused() {
        return focused;
    }

    /**
     * Registers a callback fired with the new text every time it changes through typing, backspace, or delete. A programmatic {@link #setText} does not fire it. Only one listener is held at a time.
     */
    public void onTextChanged(Consumer<String> consumer) {
        this.onTextChangedConsumer = consumer;
    }

    /**
     * Registers a callback fired with the current text when Enter/Return is pressed while focused. Only one listener is held at a time.
     */
    public void onSubmit(Consumer<String> consumer) {
        this.onSubmitConsumer = consumer;
    }

    /**
     * Registers a callback fired with {@code true}/{@code false} when this field gains/loses keyboard focus. Only one listener is held at a time.
     */
    public void onFocusChanged(Consumer<Boolean> consumer) {
        this.onFocusChangedConsumer = consumer;
    }

    @Override
    public void onFocusGained() {
        focused = true;
        resetBlink();
        if (onFocusChangedConsumer != null) {
            onFocusChangedConsumer.accept(true);
        }
    }

    @Override
    public void onFocusLost() {
        focused = false;
        history.seal();
        clearSelection(); // otherwise this field's highlight stays visible after focus (and a fresh selection) moves elsewhere
        if (onFocusChangedConsumer != null) {
            onFocusChangedConsumer.accept(false);
        }
    }

    @Override
    public void onCharTyped(int codepoint) {
        if (Character.isISOControl(codepoint)) return; // enter/backspace/etc arrive via onKeyPressed instead
        insertText(new String(Character.toChars(codepoint)));
        resetBlink();
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action == GLFW.GLFW_RELEASE) return; // act on press + auto-repeat only

        boolean shift = options.isSelectionEnabled() && (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        // Ctrl on Windows and Linux, Cmd on macOS. Either is accepted rather than
        // guessing the platform, so a Ctrl press out of habit still works on a Mac.
        boolean commandModifier = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;

        if (commandModifier) {
            switch (key) {
                case GLFW.GLFW_KEY_A -> {
                    if (options.isSelectionEnabled()) {
                        history.seal();
                        selectionAnchor = 0;
                        cursorIndex = text.length();
                        resetBlink();
                    }
                    return;
                }
                case GLFW.GLFW_KEY_Z -> {
                    if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) redo();
                    else undo();
                    return;
                }
                case GLFW.GLFW_KEY_Y -> {
                    redo();
                    return;
                }
                case GLFW.GLFW_KEY_C -> {
                    if (options.isCopyEnabled() && !password && hasSelection()) {
                        Clipboard.copy(getSelectedText());
                    }
                    return;
                }
                case GLFW.GLFW_KEY_X -> {
                    if (options.isCopyEnabled() && !password && !options.isReadOnly() && hasSelection()) {
                        Clipboard.copy(getSelectedText());
                        recordEdit(EditKind.OTHER);
                        deleteSelection();
                        fireTextChanged();
                        resetBlink();
                    }
                    return;
                }
                case GLFW.GLFW_KEY_V -> {
                    if (options.isPasteEnabled()) {
                        insertText(sanitizeForSingleLine(Clipboard.paste()));
                        resetBlink();
                    }
                    return;
                }
                default -> {
                    // Not a shortcut this field knows, so fall through to the plain key handling below.
                }
            }
        }

        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (options.isReadOnly()) return;
                if (hasSelection()) {
                    recordEdit(EditKind.OTHER);
                    deleteSelection();
                    fireTextChanged();
                } else if (cursorIndex > 0) {
                    recordEdit(EditKind.BACKSPACE);
                    int prev = Character.offsetByCodePoints(text, cursorIndex, -1);
                    text.delete(prev, cursorIndex);
                    cursorIndex = prev;
                    clearSelection(); // scrub any stray anchor; see insertText's comment on the same
                    fireTextChanged();
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (options.isReadOnly()) return;
                if (hasSelection()) {
                    recordEdit(EditKind.OTHER);
                    deleteSelection();
                    fireTextChanged();
                } else if (cursorIndex < text.length()) {
                    recordEdit(EditKind.DELETE);
                    int next = Character.offsetByCodePoints(text, cursorIndex, 1);
                    text.delete(cursorIndex, next);
                    clearSelection(); // cursorIndex doesn't move here, but keep the invariant airtight regardless
                    fireTextChanged();
                }
            }
            case GLFW.GLFW_KEY_LEFT -> moveCursorBy(-1, shift);
            case GLFW.GLFW_KEY_RIGHT -> moveCursorBy(1, shift);
            case GLFW.GLFW_KEY_HOME -> moveCursorTo(0, shift);
            case GLFW.GLFW_KEY_END -> moveCursorTo(text.length(), shift);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (onSubmitConsumer != null) {
                    onSubmitConsumer.accept(text.toString());
                }
                return; // doesn't move the cursor, so skip the blink reset below too
            }
            default -> {
                return;
            }
        }
        resetBlink();
    }

    /**
     * Moves the cursor one codepoint left ({@code direction < 0}) or right. With
     * {@code extendSelection} (shift held), grows/shrinks the selection instead of
     * just moving; without it, an existing selection first collapses to whichever
     * edge lies in the direction of travel (the standard arrow-key-with-selection
     * convention) rather than moving relative to wherever the cursor happened to be.
     */
    private void moveCursorBy(int direction, boolean extendSelection) {
        history.seal();
        if (!extendSelection && hasSelection()) {
            cursorIndex = direction < 0 ? selectionStart() : selectionEnd();
            clearSelection();
            return;
        }
        if (extendSelection && selectionAnchor < 0) {
            selectionAnchor = cursorIndex;
        }
        if (direction < 0 && cursorIndex > 0) {
            cursorIndex = Character.offsetByCodePoints(text, cursorIndex, -1);
        } else if (direction > 0 && cursorIndex < text.length()) {
            cursorIndex = Character.offsetByCodePoints(text, cursorIndex, 1);
        }
        if (!extendSelection) {
            clearSelection();
        }
    }

    /**
     * Moves the cursor to an absolute index (Home/End), extending the selection instead of collapsing it when {@code extendSelection} is set.
     */
    private void moveCursorTo(int index, boolean extendSelection) {
        history.seal();
        if (extendSelection) {
            if (selectionAnchor < 0) {
                selectionAnchor = cursorIndex;
            }
        } else {
            clearSelection();
        }
        cursorIndex = index;
    }

    private void fireTextChanged() {
        if (onTextChangedConsumer != null) {
            onTextChangedConsumer.accept(text.toString());
        }
    }

    private void resetBlink() {
        cursorVisible = true;
        blinkTimer = 0f;
    }

    /**
     * A fresh press: places the cursor at the clicked glyph and clears any existing
     * selection. It does not set {@link #selectionAnchor}, even for a plain click that
     * might turn into a drag. Only {@link #onDragUpdate} sets it, and only once the
     * mouse actually moves.
     * <p>
     * Arming a real, non-negative anchor on the press alone would leave it stale:
     * typing afterward moves {@link #cursorIndex} without going through the
     * anchor-clearing paths in {@link #moveCursorBy} and {@link #moveCursorTo}, so the
     * anchor would drift behind the cursor and {@link #hasSelection()} would report true
     * again on the next keystroke, as though the just-typed character had been selected
     * and overwritten. {@link #dragAnchorIndex} exists to remember where a drag, if one
     * happens, should extend from.
     */
    @Override
    public void onDragStart(double x, double y) {
        history.seal();
        cursorIndex = indexForX(localX(x));
        clearSelection();
        dragAnchorIndex = -1;

        if (!options.isSelectionEnabled()) {
            resetBlink();
            return;
        }

        long now = System.nanoTime();
        boolean isRepeatClick = (now - lastPressTimeNanos) <= DOUBLE_CLICK_NANOS
                && Math.hypot(x - lastPressX, y - lastPressY) <= DOUBLE_CLICK_MAX_DISTANCE;
        clickCount = isRepeatClick ? clickCount + 1 : 1;
        lastPressTimeNanos = now;
        lastPressX = x;
        lastPressY = y;

        if (clickCount >= 3) {
            // A single-line field has just the one line, so the triple-click "select line" is select-all.
            selectionAnchor = 0;
            cursorIndex = text.length();
        } else if (clickCount == 2) {
            selectWordAt(cursorIndex);
        } else {
            dragAnchorIndex = cursorIndex; // not a selection yet, just where a drag would start from
        }
        resetBlink();
    }

    /**
     * Extends the selection to follow the mouse while the button stays held, but only
     * once a drag has started one (see {@link #onDragStart}) and the mouse has moved
     * past {@link #DRAG_THRESHOLD_PIXELS} from the press point. Real hardware almost
     * always produces at least one mouse-move event between press and release, so
     * without that threshold an ordinary click would arm {@link #selectionAnchor} at a
     * fixed index while {@link #cursorIndex} keeps moving with later, unrelated edits
     * such as typing or backspace, and the selection would resurface well after the
     * click that caused it.
     */
    @Override
    public void onDragUpdate(double x, double y) {
        cursorIndex = indexForX(localX(x));
        if (dragAnchorIndex >= 0 && Math.hypot(x - lastPressX, y - lastPressY) >= DRAG_THRESHOLD_PIXELS) {
            selectionAnchor = dragAnchorIndex;
        }
        resetBlink();
    }

    @Override
    public void onDragEnd(double x, double y) {
        dragAnchorIndex = -1;
    }

    /**
     * Whether this field is a password field: every character is drawn as a {@code *}
     * instead of itself, and copy/cut are disabled so the real text can't be lifted off
     * the clipboard. {@link #getText()} still returns the real text, and typing, paste,
     * selection and caret movement all work as normal. False by default.
     */
    public boolean isPassword() {
        return password;
    }

    public void setPassword(boolean password) {
        this.password = password;
    }

    /**
     * Whether the user is blocked from changing the text. See {@link TextEditingOptions#isReadOnly()}.
     */
    public boolean isReadOnly() {
        return options.isReadOnly();
    }

    public void setReadOnly(boolean readOnly) {
        options.setReadOnly(readOnly);
    }

    /**
     * Whether Ctrl/Cmd+Z and Ctrl/Cmd+Y are active. See {@link TextEditingOptions#isUndoEnabled()}. Turning it off also forgets the existing history.
     */
    public boolean isUndoEnabled() {
        return options.isUndoEnabled();
    }

    public void setUndoEnabled(boolean undoEnabled) {
        options.setUndoEnabled(undoEnabled);
        if (!undoEnabled) {
            history.clear();
        }
    }

    /**
     * This overlay's undo/redo stack, e.g. to {@link TextUndoHistory#clear() clear} it after the app loads a
     * new document. Its size limit comes from {@link TextEditingOptions#setUndoLimit}.
     */
    public TextUndoHistory getUndoHistory() {
        return history;
    }

    /**
     * What's drawn/measured on screen: the text itself, or one {@code *} per char in {@link #isPassword() password} mode (so every index into it still lines up with {@link #text}).
     */
    private String displayText() {
        return password ? "*".repeat(text.length()) : text.toString();
    }

    /**
     * Permanent text drawn in the placeholder color at the left of the field, ahead of the editable text (e.g. {@code "Age "} or {@code "$"}). Unlike {@link #getPlaceholder()} it stays visible whether or not the field has content, and the text/caret start after it. Empty (the default) draws nothing.
     */
    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix == null ? "" : prefix;
    }

    /**
     * Whether the editable text sits against the field's right edge instead of the left while it is short enough to fit. Once it overflows, it scrolls exactly like left-aligned text. Combined with {@link #setPrefix}, this puts a label at the far left and the value at the far right. False by default.
     */
    public boolean isRightAligned() {
        return rightAligned;
    }

    public void setRightAligned(boolean rightAligned) {
        this.rightAligned = rightAligned;
    }

    /**
     * How far right-alignment shifts the text from its left-aligned position; 0 when off, empty, or overflowing.
     */
    private float alignShift() {
        float fraction = textAlignH(rightAligned ? 1f : 0f);
        if (fraction == 0f || text.length() == 0) return 0f;
        float innerWidth = (float) getWidth() - 2 * paddingX - prefixWidth();
        return Math.max(0f, innerWidth - getOrBuildFont().measureTextWidth(displayText())) * fraction;
    }

    private float prefixWidth() {
        return prefix.isEmpty() ? 0f : getOrBuildFont().measureTextWidth(prefix);
    }

    /**
     * Converts a window-space x coordinate into this field's un-scrolled text-local x, as {@link #indexForX} expects.
     */
    private float localX(double windowX) {
        return (float) (windowX - getAbsoluteX() - paddingX - prefixWidth() - alignShift() + scrollOffset);
    }

    /**
     * Expands the selection to the maximal run of characters around {@code index}
     * that share the same class (whitespace or not) as the character right at
     * {@code index}. Double-clicking a word therefore selects that word, and
     * double-clicking a run of spaces selects the whole run.
     */
    private void selectWordAt(int index) {
        if (password) { // word boundaries would leak the password's structure, so select everything instead
            selectionAnchor = 0;
            cursorIndex = text.length();
            return;
        }
        String content = text.toString();
        if (content.isEmpty()) return;

        int pos = Math.max(0, Math.min(index, content.length() - 1));
        boolean whitespace = Character.isWhitespace(content.codePointAt(pos));

        int start = pos;
        while (start > 0) {
            int prev = Character.offsetByCodePoints(content, start, -1);
            if (Character.isWhitespace(content.codePointAt(prev)) != whitespace) break;
            start = prev;
        }

        int end = pos;
        while (end < content.length()) {
            int cp = content.codePointAt(end);
            if (Character.isWhitespace(cp) != whitespace) break;
            end += Character.charCount(cp);
        }

        selectionAnchor = start;
        cursorIndex = end;
    }

    /**
     * Steps through the text one codepoint at a time to find the closest cursor gap to {@code x} (relative to the start of the text, pre-scroll).
     */
    private int indexForX(float x) {
        String content = displayText();
        if (content.isEmpty() || x <= 0) return 0;

        org.jetbrains.skia.Font font = getOrBuildFont();
        int length = content.length();
        float previousWidth = 0f;
        int previousIndex = 0;
        int i = 0;
        while (i < length) {
            int codepoint = content.codePointAt(i);
            int next = i + Character.charCount(codepoint);
            float width = font.measureTextWidth(content.substring(0, next));
            if (x < (previousWidth + width) / 2f) {
                return previousIndex;
            }
            previousWidth = width;
            previousIndex = next;
            i = next;
        }
        return length;
    }

    private org.jetbrains.skia.Font getOrBuildFont() {
        if (fontDirty || skiaFont == null) {
            if (skiaFont != null) {
                skiaFont.close(); // native resource, so release the old one
            }
            skiaFont = new org.jetbrains.skia.Font(typeface, fontSize);
            fontDirty = false;
        }
        return skiaFont;
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        // Background/border (see applyDefaultStyling) + fires the user's onRender hook.
        super.render(renderer);

        org.jetbrains.skia.Font font = getOrBuildFont();
        FontMetrics metrics = font.getMetrics();
        float textHeight = -metrics.getAscent() + metrics.getDescent();

        float prefixWidth = prefixWidth();
        float innerX = (float) getX() + paddingX + prefixWidth;
        float innerY = (float) getY() + ((float) getHeight() - textHeight) / 2f;
        float innerWidth = Math.max(0f, (float) getWidth() - 2 * paddingX - prefixWidth);
        innerX += alignShift();

        String content = displayText();
        int clampedCursor = Math.max(0, Math.min(cursorIndex, content.length()));
        float cursorX = font.measureTextWidth(content.substring(0, clampedCursor));

        // Keep the cursor within the visible inner width by adjusting the scroll offset.
        if (cursorX - scrollOffset > innerWidth) {
            scrollOffset = cursorX - innerWidth;
        }
        if (cursorX - scrollOffset < 0) {
            scrollOffset = cursorX;
        }

        renderer.pushClip((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight());

        boolean selected = hasSelection();
        if (selected && options.getSelectionColor() != null) {
            int selStart = Math.max(0, Math.min(selectionStart(), content.length()));
            int selEnd = Math.max(0, Math.min(selectionEnd(), content.length()));
            float selStartX = font.measureTextWidth(content.substring(0, selStart));
            float selEndX = font.measureTextWidth(content.substring(0, selEnd));
            float highlightX = innerX + selStartX - scrollOffset;
            renderer.drawQuad(highlightX, innerY, selEndX - selStartX, textHeight, options.getSelectionColor(), 0f);
        }

        float textDrawX = innerX - scrollOffset;
        if (!prefix.isEmpty() && options.getPlaceholderColor() != null) {
            renderer.pushClip((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight());
            renderer.drawText(prefix, (float) getX() + paddingX, innerY, font, options.getPlaceholderColor());
            renderer.popClip();
        }
        if (content.isEmpty()) {
            String placeholder = options.getPlaceholder();
            if (!placeholder.isEmpty() && options.getPlaceholderColor() != null) {
                renderer.drawText(placeholder, textDrawX, innerY, font, options.getPlaceholderColor());
            }
        } else if (options.getTextColor() != null) {
            renderer.drawText(content, textDrawX, innerY, font, resolveTextColor(options.getTextColor()));
        }

        // Selection has its own highlight above, so no blinking caret is drawn here.
        if (focused && cursorVisible && !selected) {
            drawCaret(renderer, font, content, clampedCursor, innerX, innerY, textHeight, cursorX);
        }

        renderer.popClip();
    }

    /**
     * Draws the caret per {@link TextEditingOptions#getCaretShape()} at the cursor's current position.
     */
    private void drawCaret(Renderer renderer, org.jetbrains.skia.Font font, String content, int clampedCursor, float innerX, float innerY, float textHeight, float cursorX) {
        Color caretColor = options.getCaretColor();
        if (caretColor == null) return;

        float x = innerX + cursorX - scrollOffset;

        switch (options.getCaretShape()) {
            case LINE -> {
                float height = textHeight * options.getCaretHeightRatio();
                renderer.drawQuad(x, innerY, options.getCaretWidth(), height, caretColor, 0f);
            }
            case BLOCK -> {
                float height = textHeight * options.getCaretHeightRatio();
                float width = glyphWidthAt(font, content, clampedCursor);
                renderer.drawQuad(x, innerY, width, height, caretColor, 0f);
            }
            case UNDERSCORE -> {
                float width = glyphWidthAt(font, content, clampedCursor);
                float thickness = Math.max(1f, options.getCaretWidth());
                renderer.drawQuad(x, innerY + textHeight - thickness, width, thickness, caretColor, 0f);
            }
        }
    }

    /**
     * The on-screen width of the glyph right at {@code index}, or a fallback (a space's width) if the cursor sits past the last character.
     */
    private float glyphWidthAt(org.jetbrains.skia.Font font, String content, int index) {
        if (index < content.length()) {
            int cp = content.codePointAt(index);
            int next = index + Character.charCount(cp);
            return font.measureTextWidth(content.substring(index, next));
        }
        float space = font.measureTextWidth(" ");
        return space > 0 ? space : Math.max(2f, options.getCaretWidth());
    }

    @Override
    public void update(float delta) {
        super.update(delta); // advances Styling's background/border/hover paints
        if (options.getTextColor() instanceof AnimatedPaint animated) {
            animated.advance(delta);
        }

        if (!options.isSelectionEnabled() && hasSelection()) {
            clearSelection();
        }

        if (focused) {
            blinkTimer += delta;
            if (blinkTimer >= BLINK_INTERVAL_SECONDS) {
                blinkTimer -= BLINK_INTERVAL_SECONDS;
                cursorVisible = !cursorVisible;
            }
        }
    }

    /**
     * Releases the native Font this field owns. It does not close the underlying
     * Typeface, which is shared through FontCache and may still be in use elsewhere.
     * Call this when permanently discarding the TextFieldOverlay, such as when removing
     * it from its Container for good.
     */
    public void dispose() {
        if (skiaFont != null) {
            skiaFont.close();
            skiaFont = null;
        }
    }
}
