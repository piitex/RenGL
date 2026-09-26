package me.piitex.engine.ui.text;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.theme.Overridable;

/**
 * Shared, toggleable styling and behavior for an editable text Element. It is used by
 * {@link me.piitex.engine.ui.overlays.TextFieldOverlay} and
 * {@link me.piitex.engine.ui.overlays.TextAreaOverlay}, and is meant to be reused as-is
 * by any other text-editing overlay rather than having the same settings redefined.
 * <p>
 * An overlay owns one of these by default, but they are also meant to be shared: build a
 * single instance and hand it to several overlays through their {@code setOptions(...)}
 * to keep the text color, caret style, and the rest themed identically across all of
 * them, with one edit updating every overlay that holds it.
 */
public class TextEditingOptions {

    /**
     * How the blinking caret is drawn.
     */
    public enum CaretShape {
        /**
         * A thin vertical bar between two characters, the conventional text-cursor look.
         */
        LINE,
        /**
         * A solid block spanning the glyph under the cursor, terminal/insert-mode style.
         */
        BLOCK,
        /**
         * A thin horizontal bar under the glyph under the cursor.
         */
        UNDERSCORE
    }

    private boolean selectionEnabled = true;
    private boolean copyEnabled = true;
    private boolean pasteEnabled = true;
    /**
     * Maximum number of codepoints allowed, or -1 (the default) for unlimited.
     */
    private int maxLength = -1;
    private boolean readOnly = false;
    private boolean undoEnabled = true;
    private int undoLimit = 200;

    private final Overridable<Paint> textColor = new Overridable<>(Color.BLACK);
    private String placeholder = "";
    private final Overridable<Paint> placeholderColor = new Overridable<>(Color.LIGHT_GRAY);

    private final Overridable<Color> caretColor = new Overridable<>(Color.BLACK);
    private CaretShape caretShape = CaretShape.LINE;
    /**
     * Logical-point width for {@link CaretShape#LINE}, or thickness for {@link CaretShape#UNDERSCORE}. Ignored by {@link CaretShape#BLOCK}, which always spans the full glyph width.
     */
    private float caretWidth = 2f;
    /**
     * 0-1 fraction of the line height the caret spans (LINE/BLOCK, anchored at the top of the line). Ignored by UNDERSCORE, which always sits at the bottom.
     */
    private float caretHeightRatio = 1f;

    private final Overridable<Color> selectionColor = new Overridable<>(new Color(0.60f, 0.78f, 1.0f, 0.55f));

    /**
     * Whether the user is blocked from changing the text. A read-only overlay still takes focus, shows a caret,
     * and supports selection, copy, Ctrl/Cmd+A and Enter ({@code onSubmit}), but typing, paste, cut,
     * backspace/delete and undo/redo do nothing. Programmatic {@code setText} still works. False by default.
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /**
     * Whether Ctrl/Cmd+Z (undo) and Ctrl/Cmd+Shift+Z or Ctrl/Cmd+Y (redo) are active. True by default. While
     * off, the overlay records nothing and drops whatever history it had. The history itself is per-overlay
     * even when the options are shared. See {@link TextUndoHistory}.
     */
    public boolean isUndoEnabled() {
        return undoEnabled;
    }

    public void setUndoEnabled(boolean undoEnabled) {
        this.undoEnabled = undoEnabled;
    }

    /**
     * Maximum number of undo steps each overlay keeps; the oldest are dropped first. Defaults to 200.
     */
    public int getUndoLimit() {
        return undoLimit;
    }

    public void setUndoLimit(int undoLimit) {
        this.undoLimit = Math.max(1, undoLimit);
    }

    /**
     * Whether the user can select any text at all. Disabling this also prevents copy and cut, since there is never a selection to act on.
     */
    public boolean isSelectionEnabled() {
        return selectionEnabled;
    }

    public void setSelectionEnabled(boolean selectionEnabled) {
        this.selectionEnabled = selectionEnabled;
    }

    /**
     * Whether Ctrl/Cmd+C (and the copy half of Ctrl/Cmd+X) are allowed to put the selection on the OS clipboard.
     */
    public boolean isCopyEnabled() {
        return copyEnabled;
    }

    public void setCopyEnabled(boolean copyEnabled) {
        this.copyEnabled = copyEnabled;
    }

    /**
     * Whether Ctrl/Cmd+V is allowed to insert the OS clipboard's contents.
     */
    public boolean isPasteEnabled() {
        return pasteEnabled;
    }

    public void setPasteEnabled(boolean pasteEnabled) {
        this.pasteEnabled = pasteEnabled;
    }

    /**
     * Maximum number of codepoints the text can hold, or -1 (the default) for unlimited. A typed character or paste that would exceed this is truncated/rejected rather than erroring.
     */
    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * A plain {@link Color} for a solid fill, or a {@link me.piitex.engine.ui.color.LinearGradient}/{@link me.piitex.engine.ui.color.ScrollingGradient} for a gradient/animated fill.
     */
    public Paint getTextColor() {
        return textColor.get();
    }

    public void setTextColor(Paint textColor) {
        this.textColor.set(textColor);
    }

    /**
     * Theme-facing counterpart to {@link #setTextColor}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeTextColor(Paint textColor) {
        this.textColor.applyTheme(textColor);
    }

    /**
     * Text shown (in {@link #getPlaceholderColor()}) whenever the field is empty.
     */
    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
    }

    public Paint getPlaceholderColor() {
        return placeholderColor.get();
    }

    public void setPlaceholderColor(Paint placeholderColor) {
        this.placeholderColor.set(placeholderColor);
    }

    /**
     * Theme-facing counterpart to {@link #setPlaceholderColor}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemePlaceholderColor(Paint placeholderColor) {
        this.placeholderColor.applyTheme(placeholderColor);
    }

    public Color getCaretColor() {
        return caretColor.get();
    }

    public void setCaretColor(Color caretColor) {
        this.caretColor.set(caretColor);
    }

    /**
     * Theme-facing counterpart to {@link #setCaretColor}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeCaretColor(Color caretColor) {
        this.caretColor.applyTheme(caretColor);
    }

    public CaretShape getCaretShape() {
        return caretShape;
    }

    public void setCaretShape(CaretShape caretShape) {
        this.caretShape = caretShape == null ? CaretShape.LINE : caretShape;
    }

    public float getCaretWidth() {
        return caretWidth;
    }

    public void setCaretWidth(float caretWidth) {
        this.caretWidth = caretWidth;
    }

    public float getCaretHeightRatio() {
        return caretHeightRatio;
    }

    public void setCaretHeightRatio(float caretHeightRatio) {
        this.caretHeightRatio = Math.max(0f, Math.min(1f, caretHeightRatio));
    }

    public Color getSelectionColor() {
        return selectionColor.get();
    }

    public void setSelectionColor(Color selectionColor) {
        this.selectionColor.set(selectionColor);
    }

    /**
     * Theme-facing counterpart to {@link #setSelectionColor}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeSelectionColor(Color selectionColor) {
        this.selectionColor.applyTheme(selectionColor);
    }

    /**
     * Undoes every explicit color set on this style, so they all follow the theme again, starting now.
     */
    public void followTheme() {
        textColor.clearExplicit();
        placeholderColor.clearExplicit();
        caretColor.clearExplicit();
        selectionColor.clearExplicit();
    }
}
