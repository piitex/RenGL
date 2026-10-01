package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.Clipboard;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.scroll.ScrollBarStyle;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.TextEditingOptions;
import me.piitex.engine.ui.text.TextUndoHistory;
import me.piitex.engine.ui.text.TextUndoHistory.EditKind;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import me.piitex.engine.ui.text.FontMetricsCompat;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A multi-line editable text box, the straightforward sibling of
 * {@link TextFieldOverlay}. It shares that class's {@link TextEditingOptions} (colors,
 * caret shape, copy/paste/selection toggles) wholesale, along with everything about how
 * a single line of text is selected, edited, and handled by the clipboard shortcuts.
 * What differs here is entirely about having more than one line: word wrap
 * ({@link #setWordWrap}), vertical instead of horizontal scrolling, Up/Down arrow
 * navigation, and Enter inserting a newline instead of submitting. Submit moves to
 * Ctrl/Cmd+Enter (see {@link #onKeyPressed}) and is still exposed through
 * {@link #onSubmit}.
 * <p>
 * The cursor and selection are tracked as a single flat codepoint index into the whole
 * text, newlines included, exactly like {@link TextFieldOverlay}. Only rendering and
 * hit-testing need to know about lines, which they read from a small internal
 * {@link Line} layout cache ({@link #ensureLayout()}) rebuilt lazily whenever the text,
 * the width, or {@link #setWordWrap} changes.
 * <p>
 * <b>Known gap:</b> with {@link #setWordWrap} off, an overlong line clips at this
 * Element's own edge rather than scrolling horizontally. Only vertical scrolling is
 * implemented. Turn word wrap back on (the default) if that matters.
 * <p>
 * Scrolling (mouse wheel through {@link Scrollable}, plus an optional draggable thumb)
 * is built directly into this class. A
 * {@link me.piitex.engine.ui.scroll.ScrollContainer} is not needed to make a tall box
 * with more content than fits scrollable, unlike wrapping most other Elements. The
 * thumb reuses {@link me.piitex.engine.ui.scroll.ScrollBarStyle}, the same styling
 * object {@code ScrollContainer} uses, drawn as an overlay strip on the right edge
 * rather than a reserved gutter. See that class's own "known gap" note, which applies
 * here too.
 * <p>
 * This is the plain type, with no syntax coloring and no spell or grammar checking. See
 * {@link RichTextAreaOverlay} for the subclass that adds pluggable, app-supplied inline
 * text coloring, such as an AI chat transcript with "quoted" and *emphasized* runs, on
 * top of everything here.
 */
public class TextAreaOverlay extends Overlay implements Focusable, Draggable, Scrollable, Themeable, FontScalable {
    private static final float BLINK_INTERVAL_SECONDS = 0.5f;
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final double DOUBLE_CLICK_MAX_DISTANCE = 6.0;
    private static final double DRAG_THRESHOLD_PIXELS = 4.0;

    private final StringBuilder text = new StringBuilder();
    private int cursorIndex = 0;
    /**
     * The other edge of the selection, or -1 if nothing is selected. {@link #cursorIndex} is always the active edge.
     */
    private int selectionAnchor = -1;

    private TextEditingOptions options = new TextEditingOptions();
    private final TextUndoHistory history = new TextUndoHistory();

    private float fontSize;
    private float paddingX = 8f;
    private float paddingY = 6f;
    private boolean wordWrap = true;
    private float lineSpacing = 4f;

    private Typeface typeface;
    private org.jetbrains.skia.Font skiaFont;
    private boolean fontDirty = true;

    private boolean focused = false;
    private boolean cursorVisible = false;
    private float blinkTimer = 0f;
    private float scrollOffsetY = 0f;
    private float scrollSpeed = 40f;

    private boolean scrollbarVisible = true;
    private ScrollBarStyle scrollBarStyle = new ScrollBarStyle();
    private boolean draggingThumb = false;
    private double thumbDragAnchorMouseY, thumbDragAnchorOffsetY;

    /**
     * The Line layout, rebuilt lazily by {@link #ensureLayout()}. See that method.
     */
    private final List<Line> lines = new ArrayList<>();
    private boolean layoutDirty = true;
    private double lastLayoutWidth = -1;

    /**
     * The horizontal position, in points from the start of its line, that Up/Down arrow
     * navigation tries to preserve across lines of differing length. This is the
     * standard "sticky column" editor convention. A value {@code < 0} means unset, and
     * is recomputed from {@link #cursorIndex}'s actual current position the next time it
     * is needed. Every non-vertical cursor move invalidates this explicitly;
     * {@link #moveCursorVertical} is the only thing that reads it without resetting it.
     */
    private float preferredX = -1f;

    /**
     * Consecutive presses landing within the double-click time and distance of each other: 1 for a fresh click, 2 for a double, 3 or more for a triple.
     */
    private int clickCount = 0;
    private long lastPressTimeNanos = Long.MIN_VALUE;
    private double lastPressX, lastPressY;
    private int dragAnchorIndex = -1;

    private Consumer<String> onTextChangedConsumer;
    private Consumer<String> onSubmitConsumer;
    private Consumer<Boolean> onFocusChangedConsumer;

    public TextAreaOverlay(double width, double height) {
        this(0, 0, width, height, 16f, Color.BLACK);
    }

    public TextAreaOverlay(double x, double y, double width, double height) {
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
    public TextAreaOverlay(double x, double y, double width, double height, float fontSize, Paint textColor) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);
        this.fontSize = fontSize;
        // Seeded as a baseline, not pinned. See TextFieldOverlay's own constructor for
        // why (ThemeManager.register below would otherwise be silently blocked).
        options.applyThemeTextColor(textColor);
        this.typeface = Font.getDefault();

        setCursorMode(CursorMode.TEXT);
        setFocusTraversable(true);
        ThemeManager.register(this);
    }

    /**
     * Uses a custom font file (cached via {@link Font}) instead of the system default.
     */
    public TextAreaOverlay(double x, double y, double width, double height, File fontFile, float fontSize, Paint textColor) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);
        this.fontSize = fontSize;
        options.applyThemeTextColor(textColor);
        this.typeface = Font.load(fontFile);

        setCursorMode(CursorMode.TEXT);
        setFocusTraversable(true);
        ThemeManager.register(this);
    }

    /**
     * Applies {@code theme}'s field tokens to this box (background, hover, border,
     * corner radius) and to the typed text/caret/placeholder/selection colors in
     * {@link #getOptions()}, as a baseline. This mirrors
     * {@link TextFieldOverlay#applyTheme} exactly, since both share the same
     * {@link TextEditingOptions} shape.
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
        scrollBarStyle.applyThemeTrackColor(theme.getScrollTrack());
        scrollBarStyle.applyThemeThumbColor(theme.getScrollThumb());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        options.followTheme();
        scrollBarStyle.followTheme();
    }

    /**
     * Use a custom font file instead of the system default. Cached by path, so it is cheap to call repeatedly with the same file.
     */
    public void setFontFile(File fontFile) {
        this.typeface = Font.load(fontFile);
        fontDirty = true;
        layoutDirty = true;
    }

    public void setFontFile(String fontFilePath) {
        setFontFile(new File(fontFilePath));
    }

    /**
     * The current contents, newlines included.
     */
    public String getText() {
        return text.toString();
    }

    /**
     * Replaces the contents wholesale and moves the cursor to the end. Does not fire {@link #onTextChanged}. Works on a read-only box, and discards the undo history.
     */
    public void setText(String newText) {
        history.clear();
        text.setLength(0);
        if (newText != null) {
            text.append(newText);
        }
        cursorIndex = text.length();
        clearSelection();
        preferredX = -1f;
        scrollOffsetY = 0f;
        layoutDirty = true;
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        fontDirty = true;
        layoutDirty = true;
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
     * The shared styling/behavior knobs (text/placeholder/caret/selection color, caret
     * shape and size, copy/paste/selection toggles). See {@link TextEditingOptions}.
     * This is the same class {@link TextFieldOverlay} uses; share one instance between a
     * TextFieldOverlay and a TextAreaOverlay to theme them identically.
     */
    public TextEditingOptions getOptions() {
        return options;
    }

    /**
     * Replaces this box's options wholesale. Passing null is ignored.
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
     * Text shown (in {@link #getPlaceholderColor()}) whenever the box is empty.
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
     * Whether long lines wrap to fit this box's width instead of overflowing it. On by
     * default. See the class docs' "known gap" note for what turning this off costs.
     */
    public boolean isWordWrap() {
        return wordWrap;
    }

    public void setWordWrap(boolean wordWrap) {
        this.wordWrap = wordWrap;
        layoutDirty = true;
    }

    /**
     * Extra vertical gap, in logical points, between lines, added on top of the font's own line height.
     */
    public float getLineSpacing() {
        return lineSpacing;
    }

    public void setLineSpacing(float lineSpacing) {
        this.lineSpacing = lineSpacing;
        layoutDirty = true;
    }

    /**
     * Horizontal inset, in logical points, between this box's edges and its text.
     */
    public float getPaddingX() {
        return paddingX;
    }

    public void setPaddingX(float paddingX) {
        this.paddingX = paddingX;
        layoutDirty = true;
    }

    /**
     * Vertical inset, in logical points, between this box's top/bottom edges and its text.
     */
    public float getPaddingY() {
        return paddingY;
    }

    public void setPaddingY(float paddingY) {
        this.paddingY = paddingY;
    }

    // --- Scrolling -------------------------------------------------------------------------------

    /**
     * Logical points scrolled per unit of raw wheel offset. See {@link Scrollable#onScroll}. Larger is faster.
     */
    public float getScrollSpeed() {
        return scrollSpeed;
    }

    public void setScrollSpeed(float scrollSpeed) {
        this.scrollSpeed = scrollSpeed;
    }

    /**
     * Whether a scrollbar thumb is drawn and draggable at all. Wheel and programmatic scrolling still work when this is false; there is just nothing to grab with the mouse. The thumb is also not drawn whenever there is nothing to scroll ({@link #getMaxScrollY()} {@code <= 0}), regardless of this setting. On by default.
     */
    public boolean isScrollbarVisible() {
        return scrollbarVisible;
    }

    public void setScrollbarVisible(boolean scrollbarVisible) {
        this.scrollbarVisible = scrollbarVisible;
    }

    /**
     * The colors, thickness, and corner radius used for the scrollbar thumb and track. This is the same {@link ScrollBarStyle} class {@link me.piitex.engine.ui.scroll.ScrollContainer} uses. Mutate it in place, or replace it wholesale.
     */
    public ScrollBarStyle getScrollBarStyle() {
        return scrollBarStyle;
    }

    public void setScrollBarStyle(ScrollBarStyle scrollBarStyle) {
        this.scrollBarStyle = scrollBarStyle == null ? new ScrollBarStyle() : scrollBarStyle;
    }

    public double getScrollOffsetY() {
        return scrollOffsetY;
    }

    /**
     * How much further this can scroll down, in logical points, or 0 if every line
     * already fits within this box's own height. Derived from the current
     * {@link #ensureLayout()} line count and this box's height rather than tracked
     * separately, so it stays correct automatically as text is typed, pasted, or
     * resized.
     */
    public double getMaxScrollY() {
        ensureLayout();
        float contentHeight = lines.size() * linePitch();
        float innerHeight = Math.max(0f, (float) getHeight() - 2 * paddingY);
        return Math.max(0, contentHeight - innerHeight);
    }

    /**
     * Scrolls by {@code dy} logical points relative to the current offset, clamped to {@code [0, getMaxScrollY()]}.
     */
    public void scrollBy(double dy) {
        scrollTo(scrollOffsetY + dy);
    }

    /**
     * Scrolls directly to {@code y}, clamped to {@code [0, getMaxScrollY()]}.
     */
    public void scrollTo(double y) {
        scrollOffsetY = (float) Math.max(0, Math.min(y, getMaxScrollY()));
    }

    /**
     * False when this box has nothing to scroll in the wheel's direction (everything fits, or it is already at the
     * top or bottom), so the wheel passes on to the enclosing scrollable, such as a form in a
     * {@link me.piitex.engine.ui.scroll.ScrollContainer}, instead of being swallowed here.
     */
    @Override
    public boolean canScroll(double deltaX, double deltaY) {
        if (deltaY == 0) return false;
        return deltaY > 0 ? scrollOffsetY > 0 : scrollOffsetY < getMaxScrollY();
    }

    /**
     * Mouse-wheel and trackpad scrolling. See {@link Scrollable}. The offset is negated
     * the same way {@link me.piitex.engine.ui.scroll.ScrollContainer#onScroll} negates
     * it, so a wheel gesture scrolls this box the same visual direction it would scroll
     * any other scrollable content in this engine.
     */
    @Override
    public void onScroll(double deltaX, double deltaY) {
        scrollBy(-deltaY * scrollSpeed);
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

    public boolean isFocused() {
        return focused;
    }

    /**
     * The caret's flat index into {@link #getText()} (the active edge if there's a selection), clamped to the text's bounds.
     */
    protected int getCursorIndex() {
        return Math.max(0, Math.min(cursorIndex, getText().length()));
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

    private void deleteSelection() {
        int start = selectionStart();
        int end = selectionEnd();
        text.delete(start, end);
        cursorIndex = start;
        clearSelection();
        layoutDirty = true;
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
     * allows. Unlike {@link TextFieldOverlay}'s version, newlines in {@code insert} are
     * kept as real line breaks rather than collapsed to spaces. See
     * {@link #sanitizeForMultiLine}.
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
            clearSelection();
        }

        text.insert(cursorIndex, insert);
        cursorIndex += insert.length();
        preferredX = -1f;
        layoutDirty = true;
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
        preferredX = -1f;
        layoutDirty = true;
        fireTextChanged();
        resetBlink();
    }

    /**
     * Takes back the last edit, as Ctrl/Cmd+Z does. For an app whose own Undo menu item takes Cmd+Z on macOS, which
     * keeps the key from reaching the field: the item can call this while the field has focus.
     */
    public void undo() {
        if (options.isReadOnly() || !options.isUndoEnabled()) return;
        TextUndoHistory.Snapshot previous = history.undo(snapshot());
        if (previous != null) restore(previous);
    }

    /**
     * Puts back the last edit {@link #undo()} took back, as Ctrl/Cmd+Shift+Z does.
     */
    public void redo() {
        if (options.isReadOnly() || !options.isUndoEnabled()) return;
        TextUndoHistory.Snapshot next = history.redo(snapshot());
        if (next != null) restore(next);
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
     * This box's undo/redo stack, e.g. to {@link TextUndoHistory#clear() clear} it after the app loads a
     * new document. Its size limit comes from {@link TextEditingOptions#setUndoLimit}.
     */
    public TextUndoHistory getUndoHistory() {
        return history;
    }

    private static String truncateToCodepoints(String s, int maxCodepoints) {
        if (s.codePointCount(0, s.length()) <= maxCodepoints) return s;
        return s.substring(0, s.offsetByCodePoints(0, maxCodepoints));
    }

    /**
     * Normalizes CRLF/CR to a plain {@code \n} (kept as a real line break, unlike
     * {@link TextFieldOverlay}'s single-line sanitizer), collapses a tab to a single
     * space (this box has no tab-stop layout), and drops any other control character.
     */
    private static String sanitizeForMultiLine(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\r') {
                sb.append('\n');
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
            } else if (c == '\t') {
                sb.append(' ');
            } else if (c == '\n' || !Character.isISOControl(c)) {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    /**
     * Registers a callback fired with the new text every time it changes through typing, paste, backspace, or delete. A programmatic {@link #setText} does not fire it. Only one listener is held at a time.
     */
    public void onTextChanged(Consumer<String> consumer) {
        this.onTextChangedConsumer = consumer;
    }

    /**
     * Registers a callback fired with the current text when Ctrl/Cmd+Enter is pressed while focused. Plain Enter inserts a newline instead; see {@link #onKeyPressed}. Only one listener is held at a time.
     */
    public void onSubmit(Consumer<String> consumer) {
        this.onSubmitConsumer = consumer;
    }

    /**
     * Registers a callback fired with {@code true}/{@code false} when this box gains/loses keyboard focus. Only one listener is held at a time.
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
        clearSelection();
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
        if (action == GLFW.GLFW_RELEASE) return;

        boolean shift = options.isSelectionEnabled() && (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean commandModifier = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;

        if (commandModifier) {
            switch (key) {
                case GLFW.GLFW_KEY_Z -> {
                    if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) redo();
                    else undo();
                    return;
                }
                case GLFW.GLFW_KEY_Y -> {
                    redo();
                    return;
                }
                case GLFW.GLFW_KEY_A -> {
                    if (options.isSelectionEnabled()) {
                        history.seal();
                        selectionAnchor = 0;
                        cursorIndex = text.length();
                        preferredX = -1f;
                        resetBlink();
                    }
                    return;
                }
                case GLFW.GLFW_KEY_C -> {
                    if (options.isCopyEnabled() && hasSelection()) {
                        Clipboard.copy(getSelectedText());
                    }
                    return;
                }
                case GLFW.GLFW_KEY_X -> {
                    if (options.isCopyEnabled() && !options.isReadOnly() && hasSelection()) {
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
                        insertText(sanitizeForMultiLine(Clipboard.paste()));
                        resetBlink();
                    }
                    return;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    // The one submit path. Plain Enter below inserts a newline
                    // instead, which is what makes this a text area rather than a
                    // single-line field.
                    if (onSubmitConsumer != null) {
                        onSubmitConsumer.accept(text.toString());
                    }
                    return;
                }
                default -> {
                    // Not a shortcut this box knows, so fall through to the plain key handling below.
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
                    clearSelection();
                    preferredX = -1f;
                    layoutDirty = true;
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
                    clearSelection();
                    preferredX = -1f;
                    layoutDirty = true;
                    fireTextChanged();
                }
            }
            case GLFW.GLFW_KEY_LEFT -> moveCursorBy(-1, shift);
            case GLFW.GLFW_KEY_RIGHT -> moveCursorBy(1, shift);
            case GLFW.GLFW_KEY_UP -> moveCursorVertical(-1, shift);
            case GLFW.GLFW_KEY_DOWN -> moveCursorVertical(1, shift);
            case GLFW.GLFW_KEY_HOME -> moveCursorTo(currentLineStart(), shift);
            case GLFW.GLFW_KEY_END -> moveCursorTo(currentLineEnd(), shift);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> insertText("\n");
            default -> {
                return;
            }
        }
        resetBlink();
    }

    /**
     * {@link #lineForIndex}'s start, for the line the cursor is currently on. This is what Home targets.
     */
    private int currentLineStart() {
        ensureLayout();
        return lineForIndex(cursorIndex).start;
    }

    /**
     * {@link #lineForIndex}'s end, for the line the cursor is currently on. This is what End targets.
     */
    private int currentLineEnd() {
        ensureLayout();
        return lineForIndex(cursorIndex).end;
    }

    private void moveCursorBy(int direction, boolean extendSelection) {
        history.seal();
        preferredX = -1f;
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

    private void moveCursorTo(int index, boolean extendSelection) {
        history.seal();
        preferredX = -1f;
        if (extendSelection) {
            if (selectionAnchor < 0) {
                selectionAnchor = cursorIndex;
            }
        } else {
            clearSelection();
        }
        cursorIndex = index;
    }

    /**
     * Moves the cursor up or down one visual line, trying to land on the same
     * horizontal position it started at. See {@link #preferredX}. A no-op past the first
     * or last line rather than wrapping or moving to Home/End, matching how most editors
     * treat Up from the first line and Down from the last.
     */
    private void moveCursorVertical(int direction, boolean extendSelection) {
        history.seal();
        ensureLayout();
        Line current = lineForIndex(cursorIndex);
        int currentIndex = lines.indexOf(current);
        int targetIndex = currentIndex + direction;
        if (targetIndex < 0 || targetIndex >= lines.size()) return;

        org.jetbrains.skia.Font font = getOrBuildFont();
        if (preferredX < 0) {
            int clamped = Math.max(current.start, Math.min(cursorIndex, current.end));
            preferredX = font.measureTextWidth(text.substring(current.start, clamped));
        }

        if (extendSelection && selectionAnchor < 0) {
            selectionAnchor = cursorIndex;
        } else if (!extendSelection) {
            clearSelection();
        }

        Line target = lines.get(targetIndex);
        cursorIndex = indexForXInLine(target, preferredX, font);
    }

    /**
     * Checks for a press on the scrollbar thumb first, the same thumb-before-content
     * precedence {@link me.piitex.engine.ui.scroll.ScrollContainer#onDragStart} uses for
     * its own thumb. It is needed here for the same reason: this class already
     * implements {@link Draggable} for text selection, so without checking the thumb
     * first, a press meant to grab the scrollbar would instead place the text cursor
     * underneath it.
     */
    @Override
    public void onDragStart(double x, double y) {
        ensureLayout();
        if (scrollbarVisible && thumbContains(toLocalX(x), toLocalY(y))) {
            draggingThumb = true;
            thumbDragAnchorMouseY = y;
            thumbDragAnchorOffsetY = scrollOffsetY;
            return;
        }

        history.seal();
        cursorIndex = indexForPoint(x, y);
        clearSelection();
        preferredX = -1f;
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
            selectionAnchor = 0;
            cursorIndex = text.length();
        } else if (clickCount == 2) {
            selectWordAt(cursorIndex);
        } else {
            dragAnchorIndex = cursorIndex;
        }
        resetBlink();
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (draggingThumb) {
            // Below the same DRAG_THRESHOLD_PIXELS the text-selection path already uses
            // to tell a real drag apart from an ordinary click's incidental jitter (real
            // hardware/OS input almost never reports the exact same pixel between press
            // and release). Without this, a plain click on the thumb could still see a
            // few pixels of movement, and when the thumb's own travel distance is small
            // (content barely overflows, so the thumb nearly fills the track), even that
            // tiny movement produces a ratio near 1.0, scrolling instantly to the very
            // bottom from what looked like a single click.
            if (Math.abs(y - thumbDragAnchorMouseY) < DRAG_THRESHOLD_PIXELS) {
                return;
            }
            double travel = trackHeight() - thumbLength();
            double ratio = travel > 0 ? (y - thumbDragAnchorMouseY) / travel : 0;
            scrollTo(thumbDragAnchorOffsetY + ratio * getMaxScrollY());
            return;
        }

        ensureLayout();
        cursorIndex = indexForPoint(x, y);
        preferredX = -1f;
        if (dragAnchorIndex >= 0 && Math.hypot(x - lastPressX, y - lastPressY) >= DRAG_THRESHOLD_PIXELS) {
            selectionAnchor = dragAnchorIndex;
        }
        resetBlink();
    }

    @Override
    public void onDragEnd(double x, double y) {
        if (draggingThumb) {
            draggingThumb = false;
            return;
        }
        dragAnchorIndex = -1;
    }

    // --- Scrollbar geometry ----------------------------------------------------------------------

    private double trackX() {
        return getX() + getWidth() - scrollBarStyle.getThickness() - scrollBarStyle.getMargin();
    }

    private double trackY() {
        return getY() + scrollBarStyle.getMargin();
    }

    private double trackHeight() {
        return Math.max(0, getHeight() - 2 * scrollBarStyle.getMargin());
    }

    /**
     * Total span the thumb represents: everything scrollable plus one full viewport. Used only for sizing the thumb proportionally, the same approach {@link me.piitex.engine.ui.scroll.ScrollContainer#effectiveContentHeight} takes.
     */
    private double thumbLength() {
        double trackH = trackHeight();
        double contentH = getMaxScrollY() + getHeight();
        if (contentH <= 0) return trackH;
        double raw = trackH * (getHeight() / contentH);
        return Math.max(scrollBarStyle.getMinThumbLength(), Math.min(trackH, raw));
    }

    private double thumbY() {
        double maxScroll = getMaxScrollY();
        double travel = trackHeight() - thumbLength();
        if (maxScroll <= 0 || travel <= 0) return trackY();
        return trackY() + travel * (scrollOffsetY / maxScroll);
    }

    private boolean thumbContains(double px, double py) {
        if (getMaxScrollY() <= 0) return false;
        double x = trackX(), y = thumbY(), w = scrollBarStyle.getThickness(), h = thumbLength();
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /**
     * Expands the selection to the maximal run of characters around {@code index} that
     * share the same class (whitespace or not) as the character right at {@code
     * index}, the same convention {@link TextFieldOverlay#selectWordAt} uses. {@code
     * Character.isWhitespace('\n')} is true, so this naturally stops at a paragraph
     * break without needing to special-case it.
     */
    private void selectWordAt(int index) {
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

    private void fireTextChanged() {
        if (onTextChangedConsumer != null) {
            onTextChangedConsumer.accept(text.toString());
        }
    }

    /**
     * Resets the caret blink and scrolls the cursor's line back into view if it fell
     * outside the visible area. Called from every place that moves {@link #cursorIndex},
     * such as typing, the arrow keys, a click, or Ctrl+A, and never from
     * {@link #render}. Neither {@link #onScroll} nor a scrollbar-thumb drag calls it, so
     * user-driven scrolling is left where the user put it rather than being pulled back
     * to the cursor's line on the next frame.
     */
    private void resetBlink() {
        cursorVisible = true;
        blinkTimer = 0f;
        ensureCursorVisible();
    }

    /**
     * Scrolls just enough to bring {@link #cursorIndex}'s current line fully into this box's visible inner height. A no-op if that line is already visible. See {@link #resetBlink()} for where this is called from.
     */
    private void ensureCursorVisible() {
        ensureLayout();
        String content = text.toString();
        int clampedCursor = Math.max(0, Math.min(cursorIndex, content.length()));
        Line cursorLine = lineForIndex(clampedCursor);
        int cursorLineIndex = lines.indexOf(cursorLine);
        float pitch = linePitch();
        float lineHeight = pitch - lineSpacing;
        float cursorLocalY = cursorLineIndex * pitch;
        float innerHeight = Math.max(0f, (float) getHeight() - 2 * paddingY);

        if (cursorLocalY - scrollOffsetY < 0) {
            scrollOffsetY = cursorLocalY;
        }
        if (cursorLocalY + lineHeight - scrollOffsetY > innerHeight) {
            scrollOffsetY = cursorLocalY + lineHeight - innerHeight;
        }
    }

    // --- Line layout -----------------------------------------------------------------------------

    /**
     * One visual line: {@code [start, end)} into the flat text, and whether it's terminated by a real {@code '\n'} (a paragraph break) rather than just a word-wrap point.
     */
    private static final class Line {
        final int start, end;
        final boolean hasNewline;

        Line(int start, int end, boolean hasNewline) {
            this.start = start;
            this.end = end;
            this.hasNewline = hasNewline;
        }
    }

    /**
     * Rebuilds {@link #lines} from the current text if needed. Text mutations,
     * {@link #setWordWrap}, {@link #setFontSize}/{@link #setFontFile}, and a changed
     * {@code width} (checked here directly, since resizing does not go through a setter)
     * all invalidate it. A no-op otherwise, so it is cheap to call defensively before
     * any layout-dependent read.
     */
    private void ensureLayout() {
        if (!layoutDirty && getWidth() == lastLayoutWidth) return;

        lines.clear();
        String content = text.toString();
        org.jetbrains.skia.Font font = getOrBuildFont();
        float innerWidth = wordWrap ? Math.max(1f, (float) getWidth() - 2 * paddingX) : Float.MAX_VALUE;

        int paragraphStart = 0;
        int length = content.length();
        for (int i = 0; i <= length; i++) {
            if (i == length || content.charAt(i) == '\n') {
                layoutParagraph(content, paragraphStart, i, font, innerWidth, i < length);
                paragraphStart = i + 1;
            }
        }
        if (lines.isEmpty()) {
            lines.add(new Line(0, 0, false));
        }

        lastLayoutWidth = getWidth();
        layoutDirty = false;
    }

    /**
     * Greedy word-wrap of one paragraph ({@code [start, end)}, no {@code '\n'} inside it) into one or more {@link Line}s.
     */
    private void layoutParagraph(String content, int start, int end, org.jetbrains.skia.Font font, float innerWidth, boolean hasNewline) {
        if (start == end || !wordWrap) {
            lines.add(new Line(start, end, hasNewline));
            return;
        }

        int lineStart = start;
        int lastSpace = -1;
        int i = start;
        while (i < end) {
            char c = content.charAt(i);
            float width = font.measureTextWidth(content.substring(lineStart, i + 1));
            if (width > innerWidth && i > lineStart) {
                int breakAt = lastSpace > lineStart ? lastSpace + 1 : i;
                lines.add(new Line(lineStart, breakAt, false));
                lineStart = breakAt;
                lastSpace = -1;
                continue; // re-evaluate the same i against the new lineStart
            }
            if (c == ' ') lastSpace = i;
            i++;
        }
        lines.add(new Line(lineStart, end, hasNewline));
    }

    /**
     * Which {@link Line} flat {@code index} renders on. Exact-boundary indices are
     * resolved so a real newline's index stays on the line BEFORE it (cursor sitting
     * right before the newline glyph), while a pure word-wrap boundary resolves to the
     * line after it, the start of the wrapped continuation. Resolving every case
     * perfectly would require an explicit affinity flag on the cursor; this simpler rule
     * covers the cases this class produces.
     */
    private Line lineForIndex(int index) {
        for (Line line : lines) {
            if (index >= line.start && index < line.end) return line;
        }
        for (Line line : lines) {
            if (line.end == index) return line;
        }
        return lines.get(lines.size() - 1);
    }

    /**
     * Steps through {@code line}'s text one codepoint at a time to find the closest cursor gap to local x-offset {@code x}. This is the same technique {@link TextFieldOverlay#indexForX} uses, scoped to one line and returning an absolute flat index.
     */
    private int indexForXInLine(Line line, float x, org.jetbrains.skia.Font font) {
        String content = text.toString();
        if (line.start >= line.end || x <= 0) return line.start;

        float previousWidth = 0f;
        int previousIndex = line.start;
        int i = line.start;
        while (i < line.end) {
            int codepoint = content.codePointAt(i);
            int next = i + Character.charCount(codepoint);
            float width = font.measureTextWidth(content.substring(line.start, next));
            if (x < (previousWidth + width) / 2f) {
                return previousIndex;
            }
            previousWidth = width;
            previousIndex = next;
            i = next;
        }
        return line.end;
    }

    /**
     * The flat text index nearest window-space (x, y), for subclasses that need to know what is under a click.
     */
    protected int indexAtPoint(double windowX, double windowY) {
        return indexForPoint(windowX, windowY);
    }

    /**
     * Replaces {@code [start, end)} of the text with {@code replacement} and puts the
     * cursor after it, firing the usual text-changed callback. This is the programmatic
     * equivalent of selecting that range and typing over it. A replacement that would
     * grow the text past {@link #getMaxLength()} is dropped.
     */
    protected void replaceRange(int start, int end, String replacement) {
        if (options.isReadOnly()) return;
        start = Math.max(0, Math.min(start, text.length()));
        end = Math.max(start, Math.min(end, text.length()));
        int maxLength = options.getMaxLength();
        if (maxLength >= 0) {
            int newCount = text.codePointCount(0, text.length()) - text.codePointCount(start, end)
                    + replacement.codePointCount(0, replacement.length());
            if (newCount > maxLength) return;
        }
        recordEdit(EditKind.OTHER);
        text.replace(start, end, replacement);
        cursorIndex = start + replacement.length();
        clearSelection();
        preferredX = -1f;
        layoutDirty = true;
        fireTextChanged();
    }

    /**
     * Converts a window-space (x, y) into a flat cursor index: the line by y, then {@link #indexForXInLine} within it.
     */
    private int indexForPoint(double windowX, double windowY) {
        ensureLayout();
        float pitch = linePitch();

        float localY = (float) (windowY - getAbsoluteY() - paddingY + scrollOffsetY);
        int lineIndex = pitch > 0 ? (int) Math.floor(localY / pitch) : 0;
        lineIndex = Math.max(0, Math.min(lineIndex, lines.size() - 1));

        float localX = (float) (windowX - getAbsoluteX() - paddingX);
        return indexForXInLine(lines.get(lineIndex), localX, getOrBuildFont());
    }

    private org.jetbrains.skia.Font getOrBuildFont() {
        if (fontDirty || skiaFont == null) {
            if (skiaFont != null) {
                skiaFont.close();
            }
            skiaFont = new org.jetbrains.skia.Font(typeface, fontSize);
            fontDirty = false;
        }
        return skiaFont;
    }

    /**
     * Font line height plus {@link #getLineSpacing()}, the vertical distance between consecutive lines' tops. Shared by every line-index and scroll-geometry calculation so they all agree with what {@link #render} actually draws.
     */
    private float linePitch() {
        FontMetricsCompat metrics = FontMetricsCompat.of(getOrBuildFont());
        return (-metrics.getAscent() + metrics.getDescent()) + lineSpacing;
    }

    // --- Rendering -------------------------------------------------------------------------------

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        super.render(renderer);
        ensureLayout();

        org.jetbrains.skia.Font font = getOrBuildFont();
        float pitch = linePitch();
        float lineHeight = pitch - lineSpacing;

        String content = text.toString();
        int clampedCursor = Math.max(0, Math.min(cursorIndex, content.length()));
        Line cursorLine = lineForIndex(clampedCursor);
        int cursorLineIndex = lines.indexOf(cursorLine);

        float innerX = (float) getX() + paddingX;
        float innerTop = (float) getY() + paddingY;
        float innerWidth = Math.max(0f, (float) getWidth() - 2 * paddingX);

        renderer.pushClip((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight());

        if (content.isEmpty()) {
            String placeholder = options.getPlaceholder();
            if (!placeholder.isEmpty() && options.getPlaceholderColor() != null) {
                renderer.drawText(placeholder, innerX, innerTop, font, options.getPlaceholderColor());
            }
        } else {
            boolean selected = hasSelection();
            int selStart = selected ? Math.max(0, Math.min(selectionStart(), content.length())) : -1;
            int selEnd = selected ? Math.max(0, Math.min(selectionEnd(), content.length())) : -1;

            for (int li = 0; li < lines.size(); li++) {
                Line line = lines.get(li);
                float drawY = innerTop + li * pitch - scrollOffsetY;
                if (drawY + lineHeight < getY() || drawY > getY() + getHeight()) continue; // off-screen, skip

                if (selected && selStart < line.end && selEnd > line.start) {
                    int segStart = Math.max(selStart, line.start);
                    int segEnd = Math.min(selEnd, line.end);
                    float segX = font.measureTextWidth(content.substring(line.start, segStart));
                    float segWidth = font.measureTextWidth(content.substring(line.start, segEnd)) - segX;
                    Color highlightColor = options.getSelectionColor();
                    if (highlightColor != null) {
                        renderer.drawQuad(innerX + segX, drawY, segWidth, lineHeight, highlightColor, 0f);
                    }
                }

                if (line.start < line.end && options.getTextColor() != null) {
                    drawLineText(renderer, content.substring(line.start, line.end), line.start, innerX, drawY, font);
                }
            }
        }

        // Drawn unconditionally rather than only in the non-empty branch above: an
        // empty, focused box still needs to show where typing will land, the same as a
        // fresh TextFieldOverlay does. hasSelection() is always false on empty text, so
        // the guard below behaves identically either way.
        if (focused && cursorVisible && !hasSelection()) {
            float cursorX = font.measureTextWidth(content.substring(cursorLine.start, clampedCursor));
            float cursorDrawY = innerTop + cursorLineIndex * pitch - scrollOffsetY;
            drawCaret(renderer, font, content, clampedCursor, innerX, cursorDrawY, lineHeight, cursorX);
        }

        renderer.popClip();

        if (scrollbarVisible && getMaxScrollY() > 0) {
            drawScrollbar(renderer);
        }
    }

    private void drawScrollbar(Renderer renderer) {
        Color track = scrollBarStyle.getTrackColor();
        if (track != null) {
            renderer.drawQuad((float) trackX(), (float) trackY(), scrollBarStyle.getThickness(), (float) trackHeight(), track, scrollBarStyle.getCornerRadius());
        }
        Color thumb = draggingThumb ? scrollBarStyle.getThumbActiveColor() : scrollBarStyle.getThumbColor();
        if (thumb != null) {
            renderer.drawQuad((float) trackX(), (float) thumbY(), scrollBarStyle.getThickness(), (float) thumbLength(), thumb, scrollBarStyle.getCornerRadius());
        }
    }

    /**
     * Draws one line's worth of already-clipped text at {@code (x, y)}, all in {@link
     * TextEditingOptions#getTextColor()}. This is a protected extension point:
     * {@link RichTextAreaOverlay} overrides it to split {@code lineText} into
     * differently colored runs instead of drawing it as one solid-color call, without
     * needing to touch anything else about how this class lays out or scrolls text.
     * {@code lineStart} is {@code lineText}'s own flat offset into {@link #getText()},
     * which an override needs in order to translate its own text-relative positions,
     * such as a span's start and end index, into this line's local coordinates.
     */
    protected void drawLineText(Renderer renderer, String lineText, int lineStart, float x, float y, org.jetbrains.skia.Font font) {
        renderer.drawText(lineText, x, y, font, resolveTextColor(options.getTextColor()));
    }

    /**
     * Draws the caret per {@link TextEditingOptions#getCaretShape()} at the cursor's current line and position, using the same shape logic as {@link TextFieldOverlay#drawCaret}.
     */
    private void drawCaret(Renderer renderer, org.jetbrains.skia.Font font, String content, int clampedCursor, float innerX, float lineY, float lineHeight, float cursorX) {
        Color caretColor = options.getCaretColor();
        if (caretColor == null) return;

        float x = innerX + cursorX;

        switch (options.getCaretShape()) {
            case LINE -> {
                float height = lineHeight * options.getCaretHeightRatio();
                renderer.drawQuad(x, lineY, options.getCaretWidth(), height, caretColor, 0f);
            }
            case BLOCK -> {
                float height = lineHeight * options.getCaretHeightRatio();
                float width = glyphWidthAt(font, content, clampedCursor);
                renderer.drawQuad(x, lineY, width, height, caretColor, 0f);
            }
            case UNDERSCORE -> {
                float width = glyphWidthAt(font, content, clampedCursor);
                float thickness = Math.max(1f, options.getCaretWidth());
                renderer.drawQuad(x, lineY + lineHeight - thickness, width, thickness, caretColor, 0f);
            }
        }
    }

    private float glyphWidthAt(org.jetbrains.skia.Font font, String content, int index) {
        if (index < content.length() && content.charAt(index) != '\n') {
            int cp = content.codePointAt(index);
            int next = index + Character.charCount(cp);
            return font.measureTextWidth(content.substring(index, next));
        }
        float space = font.measureTextWidth(" ");
        return space > 0 ? space : Math.max(2f, options.getCaretWidth());
    }

    @Override
    public void update(float delta) {
        super.update(delta);
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
     * Releases the native Font this box owns. It does not close the underlying Typeface; see {@link TextFieldOverlay#dispose()}, which this mirrors.
     */
    public void dispose() {
        if (skiaFont != null) {
            skiaFont.close();
            skiaFont = null;
        }
    }
}
