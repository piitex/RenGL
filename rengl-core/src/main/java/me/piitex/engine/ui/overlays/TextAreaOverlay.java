package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.Clipboard;
import me.piitex.engine.input.ImePreedit;
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
import me.piitex.engine.utils.flags.GeneratedFragments;
import me.piitex.engine.ui.text.FontMetricsCompat;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
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
 * With {@link #setWordWrap} off, a line wider than the box scrolls sideways instead: the wheel's or trackpad's
 * horizontal movement, the caret moving out of view, and a thin bar along the bottom ({@link #getMaxScrollX()}).
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
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
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
    /**
     * What the input method is composing at the caret. Unlike in a text field it is drawn over the text after the caret
     * instead of pushing it along, since reflowing every line below on each keystroke of a composition would make the
     * whole box jump.
     */
    private ImePreedit preedit = ImePreedit.NONE;
    /**
     * Where the caret was last drawn, {x, y, width, height} in window coordinates, for the input method's candidate list.
     */
    private final float[] caretRect = new float[4];
    /** How far the text is scrolled to the left, which only happens with word wrap off. */
    private float scrollOffsetX = 0f;
    /** The width of the widest line, measured when the layout is rebuilt with word wrap off. */
    private float widestLine = 0f;
    private float scrollSpeed = 40f;

    private boolean scrollbarVisible = true;
    private Float scrollbarPadding; // null: same as paddingX
    private ScrollBarStyle scrollBarStyle = new ScrollBarStyle();
    private boolean draggingThumb = false, draggingThumbX = false;
    private double thumbDragAnchorMouseX, thumbDragAnchorOffsetX;
    private double thumbDragAnchorMouseY, thumbDragAnchorOffsetY;

    /**
     * The Line layout, rebuilt lazily by {@link #ensureLayout()}. See that method.
     */
    private final List<Line> lines = new ArrayList<>();
    private boolean layoutDirty = true;
    private double lastLayoutWidth = -1;
    private float lastLayoutLane = -1;

    private static final float GUTTER_GAP = 12f;
    /** Room kept after the end of the widest line, so a caret at its end is in view. */
    private static final float CARET_ROOM = 12f;
    private BiPredicate<Integer, Integer> keyFilter;
    private BiFunction<String, Integer, String> parenCloser;
    private boolean lineNumbers = false;
    private Color lineNumberColor = new Color(0.55f, 0.55f, 0.55f, 1f);
    private Map<Integer, Color> lineMarks = Map.of();
    private int[] highlightStarts = new int[0], highlightEnds = new int[0];
    private Color highlightColor = Color.TRANSPARENT;
    /** The width kept on the left for the line numbers and the gap after them, and how much of it is the numbers. */
    private float gutter = 0f, gutterNumbers = 0f;

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
     * Uses {@code typeface} instead of the system default, such as {@link Font#monospace()} for code.
     */
    public void setTypeface(Typeface typeface) {
        this.typeface = typeface == null ? Font.getDefault() : typeface;
        fontDirty = true;
        layoutDirty = true;
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
        scrollOffsetX = 0f;
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

    public Paint getCaretColor() {
        return options.getCaretColor();
    }

    public void setCaretColor(Paint caretColor) {
        options.setCaretColor(caretColor);
    }

    public Paint getSelectionColor() {
        return options.getSelectionColor();
    }

    public void setSelectionColor(Paint selectionColor) {
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
     * Horizontal inset, in logical points, between this box's edges and its text. The scrollbar's width is kept free on the right on top of this.
     */
    public float getPaddingX() {
        return paddingX;
    }

    public void setPaddingX(float paddingX) {
        this.paddingX = paddingX;
        layoutDirty = true;
    }

    /**
     * The space, in logical points, between the right edge of the text and the scrollbar. Null, the default, uses {@link #getPaddingX()}, so it
     * is the same as the space at the left edge. Without a visible scrollbar the text keeps {@link #getPaddingX()} from the right edge.
     */
    public Float getScrollbarPadding() {
        return scrollbarPadding;
    }

    /**
     * Sets the space between the text and the scrollbar, separately from {@link #setPaddingX}. A negative value is treated as 0. Null goes back
     * to following {@link #getPaddingX()}. The text rewraps to the new width.
     */
    public void setScrollbarPadding(Float scrollbarPadding) {
        this.scrollbarPadding = scrollbarPadding == null ? null : Math.max(0f, scrollbarPadding);
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

    public double getScrollOffsetX() {
        return scrollOffsetX;
    }

    /**
     * How much further this can scroll to the right, in logical points: how much wider the widest line is than the room
     * for text. Always 0 with word wrap on.
     */
    public double getMaxScrollX() {
        ensureLayout();
        if (wordWrap) return 0;
        return Math.max(0, widestLine + CARET_ROOM - textRoom());
    }

    /** Scrolls sideways to {@code x} points, clamped to {@code [0, getMaxScrollX()]}. */
    public void scrollToX(double x) {
        scrollOffsetX = (float) Math.max(0, Math.min(x, getMaxScrollX()));
    }

    /** Scrolls sideways by {@code dx} points relative to where it is. */
    public void scrollByX(double dx) {
        scrollToX(scrollOffsetX + dx);
    }

    /** The width the text has between the gutter and the scrollbar. */
    private float textRoom() {
        return Math.max(0f, (float) getWidth() - paddingX - rightInset() - gutter);
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
        if (deltaX != 0 && Math.abs(deltaX) > Math.abs(deltaY)) {
            return deltaX > 0 ? scrollOffsetX > 0 : scrollOffsetX < getMaxScrollX();
        }
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
        if (deltaX != 0 && !wordWrap) scrollByX(-deltaX * scrollSpeed);
        if (deltaY != 0) scrollBy(-deltaY * scrollSpeed);
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
    public boolean acceptsTextInput() {
        return !options.isReadOnly();
    }

    @Override
    public void onPreedit(ImePreedit preedit) {
        this.preedit = preedit == null ? ImePreedit.NONE : preedit;
        resetBlink();
    }

    @Override
    public float[] imeCaretRect() {
        return caretRect.clone();
    }

    @Override
    public void onFocusLost() {
        focused = false;
        preedit = ImePreedit.NONE;
        history.seal();
        clearSelection();
        if (onFocusChangedConsumer != null) {
            onFocusChangedConsumer.accept(false);
        }
    }

    @Override
    public void onCharTyped(int codepoint) {
        if (Character.isISOControl(codepoint)) return; // enter/backspace/etc arrive via onKeyPressed instead
        if (options.isAutoCloseBrackets() && !options.isReadOnly()) {
            if (hasSelection() ? wrappedSelection(codepoint) : pairedBracket(codepoint)) return;
        }
        if (codepoint == '}' && options.isAutoIndent() && !options.isReadOnly() && !hasSelection()) dedentBeforeBrace();
        insertText(new String(Character.toChars(codepoint)));
        resetBlink();
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action == GLFW.GLFW_RELEASE) return;
        if (keyFilter != null && keyFilter.test(key, mods)) return;

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
                } else if (cursorIndex > 0 && options.getIndentSize() > 0 && smartBackspace()) {
                    clearSelection();
                    preferredX = -1f;
                    layoutDirty = true;
                    fireTextChanged();
                } else if (cursorIndex > 0) {
                    recordEdit(EditKind.BACKSPACE);
                    int prev = Character.offsetByCodePoints(text, cursorIndex, -1);
                    int last = emptyPairEnd(prev);
                    text.delete(prev, last > 0 ? last : cursorIndex);
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
                    int next = TextShaping.step(text.toString(), cursorIndex, 1);
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
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> insertNewline();
            case GLFW.GLFW_KEY_TAB -> {
                if (commandModifier || options.getIndentSize() <= 0 || options.isReadOnly()) return;
                if ((mods & GLFW.GLFW_MOD_SHIFT) != 0 || hasSelection()) indentLines((mods & GLFW.GLFW_MOD_SHIFT) != 0);
                else insertTab();
            }
            default -> {
                return;
            }
        }
        resetBlink();
    }

    /**
     * Offers every key press, and repeat, to {@code filter} before the box handles it, with the {@code GLFW_KEY_*} code and the
     * modifier bits. A filter that returns true has used the key, and the box ignores it. This is how a window's own
     * shortcuts, such as Ctrl+S, work while the box has the keyboard, which it otherwise keeps for itself. Pass null to
     * stop.
     */
    public void setKeyFilter(BiPredicate<Integer, Integer> filter) {
        this.keyFilter = filter;
    }

    /** Tab is this box's when it has an indent size to use it for. See {@link TextEditingOptions#setIndentSize}. */
    @Override
    public boolean wantsTab() {
        return options.getIndentSize() > 0;
    }

    /** The index where the line {@code index} is on starts: just after the Enter before it, or 0. */
    private int lineStartAt(int index) {
        return text.lastIndexOf("\n", index - 1) + 1;
    }

    /**
     * Backspace with nothing selected in a box that has an indent size, as a code editor does it: on a line that is only
     * spaces the whole line goes, and the caret is left at the end of the line before; and with only spaces before the
     * caret on a line that has something on it, the spaces go back to the stop before instead of one at a time: the
     * indentation of the line a bracket that is still open was opened on, the nearest first, or the next multiple of the
     * indent size down, whichever is closer.
     * Returns false, having done nothing, when neither is the case and Backspace is just a Backspace.
     */
    private boolean smartBackspace() {
        int lineStart = lineStartAt(cursorIndex);
        int lineEnd = text.indexOf("\n", cursorIndex);
        if (lineEnd < 0) lineEnd = text.length();
        boolean blank = true;
        for (int i = lineStart; i < lineEnd && blank; i++) blank = text.charAt(i) == ' ' || text.charAt(i) == '\t';
        if (blank && lineEnd > lineStart) {
            recordEdit(EditKind.OTHER);
            int from = lineStart > 0 ? lineStart - 1 : lineStart;
            text.delete(from, lineEnd);
            cursorIndex = from;
            return true;
        }
        int column = cursorIndex - lineStart;
        if (column == 0) return false;
        for (int i = lineStart; i < cursorIndex; i++) if (text.charAt(i) != ' ') return false;
        int size = options.getIndentSize();
        int target = (column - 1) / size * size;
        StringBuilder open = new StringBuilder();
        List<Integer> at = new ArrayList<>();
        lexAt(lineStart, open, at);
        for (int bracket : at) { // the line each bracket that is open here was opened on is a place to line up with
            int from = lineStartAt(bracket), indent = from;
            while (indent < text.length() && text.charAt(indent) == ' ') indent++;
            indent -= from;
            if (indent < column && indent > target) target = indent;
        }
        recordEdit(EditKind.OTHER);
        text.delete(lineStart + target, cursorIndex);
        cursorIndex = lineStart + target;
        return true;
    }

    /** One Tab press with nothing selected: spaces up to the next multiple of the indent size. */
    private void insertTab() {
        int size = options.getIndentSize();
        int column = cursorIndex - lineStartAt(cursorIndex);
        insertText(" ".repeat(size - column % size));
    }

    /**
     * Enter. With {@link TextEditingOptions#isAutoIndent()} the new line starts at the indentation of the line it was
     * pressed on, with one level more after an opening brace, and between a pair of braces the closing one goes on a line
     * of its own, with the caret on the line between.
     */
    private void insertNewline() {
        if (!options.isAutoIndent() || options.isReadOnly()) {
            insertText("\n");
            return;
        }
        if (options.isAutoCloseBrackets() && !hasSelection() && cursorIndex > 0 && cursorIndex < text.length() && text.charAt(cursorIndex - 1) == ')'
                && text.charAt(cursorIndex) == ';' && (cursorIndex + 1 >= text.length() || text.charAt(cursorIndex + 1) == '\n')) {
            cursorIndex++; // the ";" that ends the call stays on its line
        }
        int from = hasSelection() ? selectionStart() : cursorIndex;
        int to = hasSelection() ? selectionEnd() : cursorIndex;
        int lineStart = lineStartAt(from);
        int end = lineStart;
        while (end < from && text.charAt(end) == ' ') end++;
        String indent = text.substring(lineStart, end);
        int before = from - 1;
        while (before >= lineStart && text.charAt(before) == ' ') before--;
        boolean opens = before >= lineStart && text.charAt(before) == '{';
        boolean closes = opens && to < text.length() && text.charAt(to) == '}';
        String unit = " ".repeat(options.getIndentSize() > 0 ? options.getIndentSize() : 4);
        if (closes) {
            String tail = "\n" + indent;
            insertText("\n" + indent + unit + tail);
            cursorIndex -= tail.length();
        } else {
            insertText("\n" + indent + (opens ? unit : ""));
        }
    }

    /**
     * Puts one level of indentation on the start of each line the selection touches, or takes one off. A selection that
     * ends at the very start of a line leaves that line alone, and a blank line is not given spaces.
     */
    private void indentLines(boolean outdent) {
        int size = options.getIndentSize();
        int from = hasSelection() ? selectionStart() : cursorIndex;
        int to = hasSelection() ? selectionEnd() : cursorIndex;
        if (to > from && text.charAt(to - 1) == '\n') to--;
        int start = lineStartAt(from);
        int end = text.indexOf("\n", to);
        if (end < 0) end = text.length();

        String[] lines = text.substring(start, end).split("\n", -1);
        int[] starts = new int[lines.length]; // where each line was, in the text before
        int[] changes = new int[lines.length]; // how many characters were added to it, or taken off as a negative
        StringBuilder block = new StringBuilder();
        int at = start;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            starts[i] = at;
            at += line.length() + 1;
            if (outdent) {
                int remove = 0;
                while (remove < size && remove < line.length() && line.charAt(remove) == ' ') remove++;
                if (remove == 0 && !line.isEmpty() && line.charAt(0) == '\t') remove = 1;
                changes[i] = -remove;
                line = line.substring(remove);
            } else if (!line.isBlank()) {
                changes[i] = size;
                line = " ".repeat(size) + line;
            }
            if (i > 0) block.append('\n');
            block.append(line);
        }
        int growth = 0;
        for (int change : changes) growth += change;
        if (growth == 0) return;
        int limit = options.getMaxLength();
        if (limit >= 0 && text.codePointCount(0, text.length()) + growth > limit) return;

        recordEdit(EditKind.OTHER);
        int selected = hasSelection() ? selectionAnchor : -1;
        int newCursor = shifted(cursorIndex, starts, changes);
        int newAnchor = selected < 0 ? -1 : shifted(selected, starts, changes);
        text.replace(start, end, block.toString());
        cursorIndex = newCursor;
        selectionAnchor = newAnchor;
        preferredX = -1f;
        layoutDirty = true;
        fireTextChanged();
    }

    /** Where {@code index} is after the lines that start at {@code starts} gained or lost {@code changes} characters. */
    private static int shifted(int index, int[] starts, int[] changes) {
        int result = index;
        for (int i = 0; i < starts.length; i++) {
            if (changes[i] > 0 && index > starts[i]) result += changes[i];
            else if (changes[i] < 0) result -= Math.min(-changes[i], Math.max(0, index - starts[i]));
        }
        return result;
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
        // In right-to-left text the arrow pointing left is the one that goes on through the text.
        int lineStart = lineStartAt(cursorIndex);
        int newline = text.indexOf("\n", cursorIndex);
        if (TextShaping.isRightToLeft(text.subSequence(lineStart, newline < 0 ? text.length() : newline))) direction = -direction;
        if (direction < 0 && cursorIndex > 0) {
            cursorIndex = TextShaping.step(text.toString(), cursorIndex, -1);
        } else if (direction > 0 && cursorIndex < text.length()) {
            cursorIndex = TextShaping.step(text.toString(), cursorIndex, 1);
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
            preferredX = TextShaping.width(font, text.substring(current.start, clamped));
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

        if (scrollbarVisible && thumbXContains(toLocalX(x), toLocalY(y))) {
            draggingThumbX = true;
            thumbDragAnchorMouseX = x;
            thumbDragAnchorOffsetX = scrollOffsetX;
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
            selectWordAtPoint(x, y);
        } else {
            dragAnchorIndex = cursorIndex;
        }
        resetBlink();
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (draggingThumbX) {
            if (Math.abs(x - thumbDragAnchorMouseX) < DRAG_THRESHOLD_PIXELS) return;
            float room = textRoom();
            float thumb = Math.max(scrollBarStyle.getMinThumbLength(), Math.min(room, room * room / Math.max(1f, widestLine + CARET_ROOM)));
            double travel = room - thumb;
            scrollToX(thumbDragAnchorOffsetX + (travel > 0 ? (x - thumbDragAnchorMouseX) / travel : 0) * getMaxScrollX());
            return;
        }
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
        if (draggingThumb || draggingThumbX) {
            draggingThumb = false;
            draggingThumbX = false;
            return;
        }
        dragAnchorIndex = -1;
    }

    /**
     * Sets what typing "(" adds after it when {@link TextEditingOptions#isAutoCloseBrackets()} is on: it is given the text and
     * the index the bracket is typed at, and returns what goes after the bracket, such as ")" or ");", or null for nothing.
     * The caret stays right after the bracket. The default adds ")" when what follows is the end, a space or a closer.
     */
    public void setParenCloser(BiFunction<String, Integer, String> closer) {
        this.parenCloser = closer;
    }

    /** What a place in the text is a part of, as Java's own syntax has it. */
    private enum Lex { CODE, STRING, CHAR, LINE_COMMENT, BLOCK_COMMENT, TEXT_BLOCK }

    /**
     * What the text at {@code index} is a part of, reading from the start, so that a bracket or a quote inside a string or a
     * comment is not taken for code. If {@code open} is given, it is filled with the brackets in code that are still open there.
     */
    private Lex lexAt(int index, StringBuilder open) {
        return lexAt(index, open, null);
    }

    /** As {@link #lexAt(int, StringBuilder)}, and if {@code at} is given too, it is filled with where each of the open brackets is. */
    private Lex lexAt(int index, StringBuilder open, List<Integer> at) {
        Lex state = Lex.CODE;
        int length = Math.min(index, text.length());
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            switch (state) {
                case CODE -> {
                    if (c == '/' && i + 1 < length && text.charAt(i + 1) == '/') {
                        state = Lex.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && i + 1 < length && text.charAt(i + 1) == '*') {
                        state = Lex.BLOCK_COMMENT;
                        i++;
                    } else if (c == '"') {
                        if (i + 2 < length && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
                            state = Lex.TEXT_BLOCK;
                            i += 2;
                        } else {
                            state = Lex.STRING;
                        }
                    } else if (c == '\'') {
                        state = Lex.CHAR;
                    } else if (open != null && (c == '(' || c == '[' || c == '{')) {
                        open.append(c);
                        if (at != null) at.add(i);
                    } else if (open != null && (c == ')' || c == ']' || c == '}') && open.length() > 0) {
                        open.setLength(open.length() - 1);
                        if (at != null) at.remove(at.size() - 1);
                    }
                }
                case STRING, CHAR -> {
                    if (c == '\\') i++;
                    else if (c == '\n' || c == (state == Lex.STRING ? '"' : '\'')) state = Lex.CODE;
                }
                case LINE_COMMENT -> {
                    if (c == '\n') state = Lex.CODE;
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < length && text.charAt(i + 1) == '/') {
                        state = Lex.CODE;
                        i++;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '\\') i++;
                    else if (c == '"' && i + 2 < length && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
                        state = Lex.CODE;
                        i += 2;
                    }
                }
            }
        }
        return state;
    }

    private static boolean room(char next) {
        return next == 0 || " \t\n\r)]},;".indexOf(next) >= 0;
    }

    /**
     * Handles the brackets and the quotes for {@link TextEditingOptions#isAutoCloseBrackets()}: an opening one in code adds
     * its closing one, a closing one that is next is gone over, a ";" that came with a call is gone over. True if the
     * character was used up. Nothing is added or skipped in a string or a comment.
     */
    private boolean pairedBracket(int codepoint) {
        if (codepoint > 0xFFFF) return false;
        char c = (char) codepoint;
        char next = cursorIndex < text.length() ? text.charAt(cursorIndex) : 0;
        char prev = cursorIndex > 0 ? text.charAt(cursorIndex - 1) : 0;
        if (c == '(' || c == '[' || c == '{') {
            if (lexAt(cursorIndex, null) != Lex.CODE) return false;
            String closer;
            if (c == '(' && parenCloser != null) {
                closer = parenCloser.apply(text.toString(), cursorIndex);
            } else {
                closer = room(next) ? String.valueOf(c == '(' ? ')' : c == '[' ? ']' : '}') : null;
            }
            if (closer == null || closer.isEmpty()) return false;
            recordEdit(EditKind.OTHER);
            clearSelection();
            text.insert(cursorIndex, c + closer);
            cursorIndex++;
        } else if (c == ';' && prev == ')' && next == ';' && (cursorIndex + 1 >= text.length() || text.charAt(cursorIndex + 1) == '\n')) {
            history.seal(); // the ";" that came with the brackets is the one being typed
            clearSelection();
            cursorIndex++;
        } else if ((c == ')' || c == ']' || c == '}') && next == c) {
            StringBuilder open = new StringBuilder();
            if (lexAt(cursorIndex, open) != Lex.CODE || open.length() == 0 || open.charAt(open.length() - 1) != (c == ')' ? '(' : c == ']' ? '[' : '{')) return false;
            history.seal();
            clearSelection();
            cursorIndex++;
        } else if (c == '"' || c == '\'') {
            Lex state = lexAt(cursorIndex, null);
            if (state == (c == '"' ? Lex.STRING : Lex.CHAR) && next == c && prev != '\\') {
                history.seal(); // the end of the text that was begun
                clearSelection();
                cursorIndex++;
            } else if (state == Lex.CODE && room(next) && !Character.isJavaIdentifierPart(prev) && prev != c) {
                recordEdit(EditKind.OTHER);
                clearSelection();
                text.insert(cursorIndex, "" + c + c);
                cursorIndex++;
            } else {
                return false;
            }
        } else {
            return false;
        }
        preferredX = -1f;
        layoutDirty = true;
        fireTextChanged();
        resetBlink();
        return true;
    }

    /**
     * With text selected, typing an opening bracket or a quote in code puts the pair around the selection instead of
     * replacing it, and the selection stays on the same text so another one can go round it. True if it was used up.
     */
    private boolean wrappedSelection(int codepoint) {
        if (codepoint > 0xFFFF || !hasSelection()) return false;
        char c = (char) codepoint;
        char closer = switch (c) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '"', '\'' -> c;
            default -> 0;
        };
        if (closer == 0) return false;
        int start = selectionStart(), end = selectionEnd();
        if (lexAt(start, null) != Lex.CODE) return false;
        boolean forward = cursorIndex >= selectionAnchor;
        recordEdit(EditKind.OTHER);
        text.insert(end, closer);
        text.insert(start, c);
        selectionAnchor = forward ? start + 1 : end + 1;
        cursorIndex = forward ? end + 1 : start + 1;
        preferredX = -1f;
        layoutDirty = true;
        fireTextChanged();
        resetBlink();
        return true;
    }

    /**
     * Where an empty pair that Backspace is about to cut the opening character of ends, or -1 if it isn't one: the closing
     * one right after, and for a "(" a ";" after that if it is the last thing on the line, so a call that was only just opened
     * goes whole.
     */
    private int emptyPairEnd(int open) {
        if (!options.isAutoCloseBrackets() || cursorIndex >= text.length()) return -1;
        char opener = text.charAt(open), closer = text.charAt(cursorIndex);
        boolean pair = opener == '(' && closer == ')' || opener == '[' && closer == ']' || opener == '{' && closer == '}';
        if (!pair) {
            pair = (opener == '"' || opener == '\'') && closer == opener && lexAt(open, null) == Lex.CODE;
        }
        if (!pair) return -1;
        int end = cursorIndex + 1;
        if (opener == '(' && end < text.length() && text.charAt(end) == ';' && (end + 1 >= text.length() || text.charAt(end + 1) == '\n')) end++;
        return end;
    }

    /** A closing brace typed on a line that so far is only spaces takes one level of indentation off the line. */
    private void dedentBeforeBrace() {
        int lineStart = lineStartAt(cursorIndex);
        int size = options.getIndentSize() > 0 ? options.getIndentSize() : 4;
        int spaces = cursorIndex - lineStart;
        if (spaces <= 0) return;
        for (int i = lineStart; i < cursorIndex; i++) if (text.charAt(i) != ' ') return;
        int remove = Math.min(size, spaces);
        recordEdit(EditKind.OTHER);
        text.delete(cursorIndex - remove, cursorIndex);
        cursorIndex -= remove;
        layoutDirty = true;
    }

    // --- Line numbers and positions --------------------------------------------------------------

    /** Whether a column of line numbers is drawn down the left. */
    public boolean isLineNumbers() {
        return lineNumbers;
    }

    /** Draws a column of line numbers down the left, one for each line of the text, as a code editor has. */
    public void setLineNumbers(boolean lineNumbers) {
        this.lineNumbers = lineNumbers;
        layoutDirty = true;
    }

    /** The color of the line numbers. A grey by default. */
    public void setLineNumberColor(Color color) {
        this.lineNumberColor = color == null ? new Color(0.55f, 0.55f, 0.55f, 1f) : color;
    }

    /**
     * Washes whole lines in a color, such as the lines a compiler found fault with. The keys are line numbers from 1,
     * counting lines of the text, not of the wrapped rows they are drawn in. Pass an empty map for none.
     */
    public void setLineMarks(Map<Integer, Color> marks) {
        this.lineMarks = marks == null ? Map.of() : Map.copyOf(marks);
    }

    /**
     * Washes stretches of the text in a color, behind the text, such as every match of a search. Each range is
     * {@code {start, end}}, as indexes into the text with the end not included. They are drawn under the selection, may touch
     * but are expected not to overlap, and are not moved when the text is edited, so set them again after an edit. Pass an
     * empty list for none.
     */
    public void setHighlights(java.util.List<int[]> ranges, Color color) {
        java.util.List<int[]> sorted = ranges == null ? java.util.List.of() : ranges.stream().filter(r -> r[1] > r[0]).sorted(java.util.Comparator.comparingInt(r -> r[0])).toList();
        highlightStarts = new int[sorted.size()];
        highlightEnds = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            highlightStarts[i] = sorted.get(i)[0];
            highlightEnds[i] = sorted.get(i)[1];
        }
        highlightColor = color == null ? Color.TRANSPARENT : color;
    }

    /** How many stretches of the text are highlighted. */
    public int getHighlightCount() {
        return highlightStarts.length;
    }

    /** Draws the highlights that fall in {@code line}, with {@code content} the text. */
    private void drawHighlights(Renderer renderer, org.jetbrains.skia.Font font, String content, Line line, float innerX, float drawY, float lineHeight) {
        if (highlightStarts.length == 0 || highlightColor.getA() <= 0f) return;
        // the first range that ends after the line starts, as the starts are in order and none overlap
        int low = 0, high = highlightEnds.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (highlightEnds[mid] <= line.start) low = mid + 1;
            else high = mid;
        }
        for (int i = low; i < highlightStarts.length && highlightStarts[i] < line.end; i++) {
            int from = Math.max(highlightStarts[i], line.start), to = Math.min(highlightEnds[i], Math.min(line.end, content.length()));
            if (to <= from) continue;
            float x = TextShaping.width(font, content.substring(line.start, from));
            float width = TextShaping.width(font, content.substring(line.start, to)) - x;
            renderer.drawQuad(innerX + x, drawY, width, lineHeight, highlightColor, 0f);
        }
    }

    /** How many lines the text has: one more than it has line breaks, so an empty text has one. */
    public int getLineCount() {
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    /** The line the caret is on, from 1. */
    public int getCursorLine() {
        int line = 1;
        int end = Math.max(0, Math.min(cursorIndex, text.length()));
        for (int i = 0; i < end; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    /** How many characters the caret is from the start of its line, plus 1. */
    public int getCursorColumn() {
        int index = Math.max(0, Math.min(cursorIndex, text.length()));
        return index - lineStartAt(index) + 1;
    }

    /**
     * Puts the caret at the start of line {@code line} (from 1, clamped to the text) with nothing selected, and scrolls
     * to show it in the middle of the box if it wasn't already in view.
     */
    public void goToLine(int line) {
        history.seal();
        int target = Math.max(1, Math.min(line, getLineCount()));
        int index = 0;
        for (int seen = 1; seen < target; index++) if (text.charAt(index) == '\n') seen++;
        cursorIndex = index;
        clearSelection();
        preferredX = -1f;
        ensureLayout();
        float pitch = linePitch();
        int row = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).number == target) {
                row = i;
                break;
            }
        }
        float innerHeight = Math.max(0f, (float) getHeight() - 2 * paddingY);
        float top = row * pitch;
        if (top < scrollOffsetY || top + pitch > scrollOffsetY + innerHeight) {
            scrollTo(top - (innerHeight - pitch) / 2f);
        }
        resetBlink();
    }

    /** The caret's index into {@link #getText()}, from 0. With a selection it is the edge that moves. */
    public int getCaretIndex() {
        return getCursorIndex();
    }

    /** Puts the caret at {@code index} (clamped to the text) with nothing selected, and scrolls to show it. */
    public void setCaretIndex(int index) {
        history.seal();
        cursorIndex = Math.max(0, Math.min(index, text.length()));
        clearSelection();
        preferredX = -1f;
        resetBlink();
    }

    /**
     * Selects {@code [start, end)} of the text (both clamped to it), with the caret at {@code end}, and scrolls to show the
     * caret. An empty range is only the caret.
     */
    public void setSelection(int start, int end) {
        history.seal();
        int from = Math.max(0, Math.min(start, text.length()));
        int to = Math.max(0, Math.min(end, text.length()));
        cursorIndex = to;
        selectionAnchor = from == to ? -1 : from;
        preferredX = -1f;
        resetBlink();
    }

    /**
     * Replaces {@code [start, end)} of the text with {@code replacement}, as if it were selected and typed over, with the
     * caret after it. It is one step to undo and fires the text-changed callback. Nothing happens in a read-only box.
     */
    public void replaceText(int start, int end, String replacement) {
        replaceRange(start, end, replacement);
        resetBlink();
    }

    /** The line of the text, from 1, that is under the window point (x, y), for a click on it. */
    public int getLineAt(double windowX, double windowY) {
        int index = indexForPoint(windowX, windowY);
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    /**
     * The index of the character that is under the window point (x, y), or -1 when there is none: the point is in the line
     * numbers, past the end of a line or of the text, or between the lines. For what a resting pointer is over, as against
     * the caret gap that a click is nearest to.
     */
    public int getCharAt(double windowX, double windowY) {
        ensureLayout();
        float pitch = linePitch();
        float localY = (float) (windowY - getAbsoluteY() - paddingY + scrollOffsetY);
        if (pitch <= 0 || localY < 0) return -1;
        int lineIndex = (int) Math.floor(localY / pitch);
        if (lineIndex >= lines.size() || localY - lineIndex * pitch >= pitch - lineSpacing) return -1;
        float localX = (float) (windowX - getAbsoluteX() - paddingX - gutter + scrollOffsetX);
        if (localX < 0) return -1;
        return charIndexInLine(lines.get(lineIndex), localX, getOrBuildFont());
    }

    /** Where the caret is drawn, in the window: its x, the y of its top, and its height. For something to show beside it. */
    public double[] getCaretBounds() {
        return getBoundsAt(getCursorIndex());
    }

    /**
     * Where the gap before the character at {@code index} (clamped to the text) is drawn, in the window: its x, the y of the top
     * of its line, and the line's height. As {@link #getCaretBounds()}, for any place in the text.
     */
    public double[] getBoundsAt(int index) {
        ensureLayout();
        int clamped = Math.max(0, Math.min(index, text.length()));
        Line caretLine = lineForIndex(clamped);
        float pitch = linePitch();
        org.jetbrains.skia.Font font = getOrBuildFont();
        double x = getAbsoluteX() + paddingX + gutter - scrollOffsetX + TextShaping.width(font, text.substring(caretLine.start, clamped));
        double y = getAbsoluteY() + paddingY + lines.indexOf(caretLine) * pitch - scrollOffsetY;
        return new double[]{x, y, pitch - lineSpacing};
    }

    /** Works out how wide the line numbers need to be for the number of lines the text has. */
    private void measureGutter(String content, org.jetbrains.skia.Font font) {
        if (!lineNumbers) {
            gutter = 0f;
            gutterNumbers = 0f;
            return;
        }
        int count = 1;
        for (int i = 0; i < content.length(); i++) if (content.charAt(i) == '\n') count++;
        int digits = Math.max(2, String.valueOf(count).length());
        gutterNumbers = TextShaping.width(font, "0") * digits;
        gutter = gutterNumbers + GUTTER_GAP;
    }

    private void drawLineMarks(Renderer renderer, float pitch, float innerTop) {
        if (lineMarks.isEmpty()) return;
        float width = (float) getWidth() - scrollbarLane();
        for (int li = 0; li < lines.size(); li++) {
            Color mark = lineMarks.get(lines.get(li).number);
            if (mark == null) continue;
            float drawY = innerTop + li * pitch - scrollOffsetY;
            if (drawY + pitch < getY() || drawY > getY() + getHeight()) continue;
            renderer.drawQuad((float) getX(), drawY, width, pitch, mark, 0f);
        }
    }

    private void drawGutter(Renderer renderer, org.jetbrains.skia.Font font, float pitch, float lineHeight, float innerTop, int currentLine) {
        if (!lineNumbers) return;
        float left = (float) getX() + paddingX;
        for (int li = 0; li < lines.size(); li++) {
            Line line = lines.get(li);
            if (!line.first) continue;
            float drawY = innerTop + li * pitch - scrollOffsetY;
            if (drawY + lineHeight < getY() || drawY > getY() + getHeight()) continue;
            String label = Integer.toString(line.number);
            Paint color = line.number == currentLine && focused ? resolveTextColor(options.getTextColor()) : lineNumberColor;
            renderer.drawText(label, left + gutterNumbers - TextShaping.width(font, label), drawY, font, color);
        }
        float rule = left + gutterNumbers + GUTTER_GAP / 2f - 0.5f;
        renderer.drawQuad(rule, (float) getY(), 1f, (float) getHeight(), new Color(lineNumberColor.getR(), lineNumberColor.getG(), lineNumberColor.getB(), 0.35f), 0f);
    }

    // --- Scrollbar geometry ----------------------------------------------------------------------

    /**
     * Width kept free on the right for the scrollbar, so text wraps before it instead of running underneath. Reserved whenever the scrollbar is enabled, not only while it is showing, so the text does not reflow when it appears.
     */
    private float scrollbarLane() {
        return scrollbarVisible ? scrollBarStyle.getThickness() + scrollBarStyle.getMargin() : 0f;
    }

    /**
     * How far the text's right edge is from the box's right edge: the scrollbar's lane plus the gap before it.
     */
    private float rightInset() {
        float gap = scrollbarVisible && scrollbarPadding != null ? scrollbarPadding : paddingX;
        return scrollbarLane() + gap;
    }

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

    /** Whether the local point is on the bar along the bottom (the whole strip of it, so it is easy to hit). */
    private boolean thumbXContains(double px, double py) {
        if (getMaxScrollX() <= 0) return false;
        float thickness = scrollBarStyle.getThickness(), margin = scrollBarStyle.getMargin();
        double top = getHeight() - thickness - margin - 3;
        return py >= top && px >= paddingX + gutter && px <= paddingX + gutter + textRoom();
    }

    private boolean thumbContains(double px, double py) {
        if (getMaxScrollY() <= 0) return false;
        double x = trackX(), y = thumbY(), w = scrollBarStyle.getThickness(), h = thumbLength();
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /**
     * A double click at window-space (x, y): the word under the pointer, or the whole line when the pointer is past the end of the line's text (or on an empty one). It is the character under the pointer that counts, not the gap the caret lands in, so the right half of a letter still picks the word it is part of.
     */
    private void selectWordAtPoint(double windowX, double windowY) {
        ensureLayout();
        float pitch = linePitch();
        float localY = (float) (windowY - getAbsoluteY() - paddingY + scrollOffsetY);
        int lineIndex = pitch > 0 ? (int) Math.floor(localY / pitch) : 0;
        lineIndex = Math.max(0, Math.min(lineIndex, lines.size() - 1));
        Line line = lines.get(lineIndex);
        float localX = (float) (windowX - getAbsoluteX() - paddingX - gutter + scrollOffsetX);

        int under = charIndexInLine(line, localX, getOrBuildFont());
        if (under < 0) {
            selectLineAt(line.end);
        } else {
            selectWordAt(under);
        }
    }

    /**
     * The index of the character whose width on {@code line} covers local x-offset {@code x}, or -1 when {@code x} is past the last one (or the line is empty).
     */
    private int charIndexInLine(Line line, float x, org.jetbrains.skia.Font font) {
        String content = text.toString();
        if (line.start >= line.end) return -1;
        if (x < 0) return line.start;

        int i = line.start;
        while (i < line.end) {
            int next = i + Character.charCount(content.codePointAt(i));
            if (x < TextShaping.width(font, content.substring(line.start, next))) return i;
            i = next;
        }
        return -1;
    }

    /**
     * Selects the line of the text (up to its newline, not through it) that {@code index} is on, however many rows it wraps over.
     */
    private void selectLineAt(int index) {
        int at = Math.max(0, Math.min(index, text.length()));
        int start = at;
        while (start > 0 && text.charAt(start - 1) != '\n') start--;
        int end = at;
        while (end < text.length() && text.charAt(end) != '\n') end++;
        selectionAnchor = start;
        cursorIndex = end;
    }

    private static final int WORD = 0, SPACE = 1, SYMBOL = 2, BRACKET = 3, BREAK = 4;

    private static int wordClass(int codePoint) {
        if (codePoint == '\n' || codePoint == '\r') return BREAK;
        if (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '$') return WORD;
        if (Character.isWhitespace(codePoint)) return SPACE;
        return "()[]{}<>\"'`".indexOf(codePoint) >= 0 ? BRACKET : SYMBOL;
    }

    /**
     * Selects the word around the character at {@code index}: a run of letters, digits and underscores, a run of spaces, or a run of other symbols such as {@code ->}. A bracket or a quote is a word of its own, so a double click on {@code foo(bar)} takes {@code foo} or {@code bar} and not the lot. A line break is never part of the run.
     */
    private void selectWordAt(int index) {
        String content = text.toString();
        if (content.isEmpty()) return;

        int pos = Math.max(0, Math.min(index, content.length() - 1));
        int kind = wordClass(content.codePointAt(pos));

        int start = pos;
        int end = pos + Character.charCount(content.codePointAt(pos));
        if (kind != BRACKET && kind != BREAK) {
            while (start > 0) {
                int prev = Character.offsetByCodePoints(content, start, -1);
                if (wordClass(content.codePointAt(prev)) != kind) break;
                start = prev;
            }
            while (end < content.length()) {
                int cp = content.codePointAt(end);
                if (wordClass(cp) != kind) break;
                end += Character.charCount(cp);
            }
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
        if (wordWrap) {
            scrollOffsetX = 0f;
        } else {
            float room = textRoom();
            float caretX = TextShaping.width(getOrBuildFont(), content.substring(cursorLine.start, clampedCursor));
            if (caretX < scrollOffsetX) scrollOffsetX = caretX;
            if (caretX + CARET_ROOM > scrollOffsetX + room) scrollOffsetX = caretX + CARET_ROOM - room;
            scrollOffsetX = (float) Math.max(0, Math.min(scrollOffsetX, getMaxScrollX()));
        }
    }

    // --- Line layout -----------------------------------------------------------------------------

    /**
     * One visual line: {@code [start, end)} into the flat text, and whether it's terminated by a real {@code '\n'} (a paragraph break) rather than just a word-wrap point.
     */
    private static final class Line {
        final int start, end;
        final boolean hasNewline;
        /** Which line of the text this belongs to, from 1, and whether it is the first of the visual lines that make it up. */
        int number;
        boolean first;

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
        if (!layoutDirty && getWidth() == lastLayoutWidth && rightInset() == lastLayoutLane) return;

        lines.clear();
        String content = text.toString();
        org.jetbrains.skia.Font font = getOrBuildFont();
        measureGutter(content, font);
        float innerWidth = wordWrap ? Math.max(1f, (float) getWidth() - paddingX - rightInset() - gutter) : Float.MAX_VALUE;

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
        widestLine = 0f;
        if (!wordWrap) {
            for (Line line : lines) {
                if (line.end > line.start) widestLine = Math.max(widestLine, TextShaping.width(font, content.substring(line.start, line.end)));
            }
        }
        int number = 0;
        boolean first = true;
        for (Line line : lines) {
            if (first) number++;
            line.number = number;
            line.first = first;
            first = line.hasNewline;
        }

        lastLayoutWidth = getWidth();
        lastLayoutLane = rightInset();
        layoutDirty = false;
        scrollOffsetX = wordWrap ? 0f : (float) Math.max(0, Math.min(scrollOffsetX, Math.max(0, widestLine + CARET_ROOM - textRoom())));
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
            float width = TextShaping.width(font, content.substring(lineStart, i + 1));
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
            float width = TextShaping.width(font, content.substring(line.start, next));
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

        float localX = (float) (windowX - getAbsoluteX() - paddingX - gutter + scrollOffsetX);
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

        float innerX = (float) getX() + paddingX + gutter - scrollOffsetX;
        float innerTop = (float) getY() + paddingY;
        float innerWidth = textRoom();

        renderer.pushClip((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight());
        drawLineMarks(renderer, pitch, innerTop);
        boolean scrolledSideways = scrollOffsetX > 0f;
        if (scrolledSideways) {
            renderer.pushClip((float) getX() + paddingX + gutter, (float) getY(), innerWidth, (float) getHeight());
        }

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

                drawHighlights(renderer, font, content, line, innerX, drawY, lineHeight);

                if (selected && selStart < line.end && selEnd > line.start) {
                    int segStart = Math.max(selStart, line.start);
                    int segEnd = Math.min(selEnd, line.end);
                    float segX = TextShaping.width(font, content.substring(line.start, segStart));
                    float segWidth = TextShaping.width(font, content.substring(line.start, segEnd)) - segX;
                    Paint highlightColor = options.getSelectionColor();
                    if (highlightColor != null) {
                        renderer.drawQuad(innerX + segX, drawY, segWidth, lineHeight, highlightColor, 0f);
                    }
                }

                if (line.start < line.end && options.getTextColor() != null) {
                    drawLineText(renderer, content.substring(line.start, line.end), line.start, innerX, drawY, font);
                }
            }
        }

        if (scrolledSideways) renderer.popClip();
        drawGutter(renderer, font, pitch, lineHeight, innerTop, cursorLine.number);
        if (scrolledSideways) {
            renderer.pushClip((float) getX() + paddingX + gutter, (float) getY(), innerWidth, (float) getHeight());
        }

        // Drawn unconditionally rather than only in the non-empty branch above: an
        // empty, focused box still needs to show where typing will land, the same as a
        // fresh TextFieldOverlay does. hasSelection() is always false on empty text, so
        // the guard below behaves identically either way.
        float cursorX = TextShaping.xAt(font, content.substring(cursorLine.start, cursorLine.end), clampedCursor - cursorLine.start);
        float cursorDrawY = innerTop + cursorLineIndex * pitch - scrollOffsetY;
        ImePreedit composing = acceptsTextInput() ? preedit : ImePreedit.NONE;
        float composingCaretX = cursorX;
        if (!composing.isEmpty()) {
            composingCaretX = cursorX + TextShaping.xAt(font, composing.text(), composing.caret());
            drawComposition(renderer, font, composing, innerX + cursorX, cursorDrawY, lineHeight);
        }
        caretRect[0] = innerX + composingCaretX;
        caretRect[1] = cursorDrawY;
        caretRect[2] = Math.max(1f, options.getCaretWidth());
        caretRect[3] = lineHeight;
        if (focused && cursorVisible && (!hasSelection() || !composing.isEmpty())) {
            drawCaret(renderer, font, content, clampedCursor, innerX, cursorDrawY, lineHeight, composingCaretX);
        }
        if (scrolledSideways) renderer.popClip();

        renderer.popClip();

        if (scrollbarVisible && getMaxScrollY() > 0) {
            drawScrollbar(renderer);
        }
        if (scrollbarVisible && getMaxScrollX() > 0) {
            drawHorizontalScrollbar(renderer);
        }
    }

    /** The thin bar along the bottom that shows how far the text is scrolled sideways. */
    private void drawHorizontalScrollbar(Renderer renderer) {
        float thickness = scrollBarStyle.getThickness(), margin = scrollBarStyle.getMargin();
        float left = (float) getX() + paddingX + gutter, room = textRoom();
        float y = (float) (getY() + getHeight()) - thickness - margin;
        Paint track = scrollBarStyle.getTrackColor();
        if (track != null) renderer.drawQuad(left, y, room, thickness, track, scrollBarStyle.getCornerRadius());
        float span = widestLine + CARET_ROOM;
        float thumb = Math.max(scrollBarStyle.getMinThumbLength(), Math.min(room, room * room / Math.max(1f, span)));
        double max = getMaxScrollX();
        float x = left + (float) (max > 0 ? (room - thumb) * (scrollOffsetX / max) : 0);
        Paint color = draggingThumbX ? scrollBarStyle.getThumbActiveColor() : scrollBarStyle.getThumbColor();
        if (color != null) renderer.drawQuad(x, y, thumb, thickness, color, scrollBarStyle.getCornerRadius());
    }

    private void drawScrollbar(Renderer renderer) {
        Paint track = scrollBarStyle.getTrackColor();
        if (track != null) {
            renderer.drawQuad((float) trackX(), (float) trackY(), scrollBarStyle.getThickness(), (float) trackHeight(), track, scrollBarStyle.getCornerRadius());
        }
        Paint thumb = draggingThumb ? scrollBarStyle.getThumbActiveColor() : scrollBarStyle.getThumbColor();
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
     * Draws the text the input method is composing at {@code (x, y)}, over a patch of the background so the text it covers
     * does not show through, underlined and with a thicker underline under the clause being converted.
     */
    private void drawComposition(Renderer renderer, org.jetbrains.skia.Font font, ImePreedit composing, float x, float y, float lineHeight) {
        float width = TextShaping.width(font, composing.text());
        Paint background = AnimatedPaint.resolve(getStyling().getBackgroundColor());
        if (background != null) renderer.drawQuad(x, y, width, lineHeight, background, 0f);
        Paint textColor = resolveTextColor(options.getTextColor());
        renderer.drawText(composing.text(), x, y, font, textColor);
        float baseline = y + lineHeight - 1f;
        renderer.drawQuad(x, baseline, width, 1f, textColor, 0f);
        if (composing.focusEnd() > composing.focusStart()) {
            float from = TextShaping.xAt(font, composing.text(), composing.focusStart());
            float to = TextShaping.xAt(font, composing.text(), composing.focusEnd());
            renderer.drawQuad(x + Math.min(from, to), baseline - 1f, Math.abs(to - from), 2f, textColor, 0f);
        }
    }

    /**
     * Draws the caret per {@link TextEditingOptions#getCaretShape()} at the cursor's current line and position, using the same shape logic as {@link TextFieldOverlay#drawCaret}.
     */
    private void drawCaret(Renderer renderer, org.jetbrains.skia.Font font, String content, int clampedCursor, float innerX, float lineY, float lineHeight, float cursorX) {
        Paint caretColor = options.getCaretColor();
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
            return TextShaping.width(font, content.substring(index, next));
        }
        float space = TextShaping.width(font, " ");
        return space > 0 ? space : Math.max(2f, options.getCaretWidth());
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        options.advance(delta);
        scrollBarStyle.advance(delta);
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
