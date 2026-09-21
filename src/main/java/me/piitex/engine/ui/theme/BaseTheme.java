package me.piitex.engine.ui.theme;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;

/**
 * The engine's own mutable {@link Theme} implementation: a value object with the same
 * shape as {@link me.piitex.engine.ui.color.Styling} and
 * {@link me.piitex.engine.ui.text.TextEditingOptions} elsewhere in this engine. Every
 * field defaults to {@link LightTheme}'s values, so {@code new BaseTheme()} and
 * {@code new LightTheme()} start identical. Extend this class, or one of its
 * {@link LightTheme} and {@link DarkTheme} subclasses, and set the tokens that should
 * differ in the constructor, or build a plain instance and adjust it with these setters
 * directly.
 */
public class BaseTheme implements Theme {
    private Color text = Color.BLACK;
    private Color fieldBackground = Color.WHITE;
    private Color fieldHoverBackground = Color.WHITE;
    private Color fieldBorder = Color.LIGHT_GRAY;
    private Color placeholderText = Color.LIGHT_GRAY;
    private Color caret = Color.BLACK;
    private Color selection = new Color(0.60f, 0.78f, 1.0f, 0.55f);

    private Color buttonBackground = new Color(0.95f, 0.95f, 0.95f, 1f);
    private Color buttonHoverBackground = new Color(0.85f, 0.85f, 0.85f, 1f);
    private Color buttonBorder = Color.DARK_GRAY;

    private Color containerBackground = Color.WHITE;
    private Color containerBorder = Color.LIGHT_GRAY;

    // Scroll bar
    private Color scrollThumb = Color.LIGHT_GRAY;
    private Color scrollTrack = new Color(0.9f, 0.9f, 0.9f, 1);

    private Color tooltipBackground = new Color(0.15f, 0.15f, 0.16f, 0.96f);
    private Color tooltipBorder = new Color(0.35f, 0.35f, 0.38f, 1f);
    private Color tooltipText = Color.WHITE;

    private Paint windowBackground = Color.WHITE;

    private float cornerRadius = 6f;
    private float borderThickness = 1f;

    @Override
    public Color getText() {
        return text;
    }

    public void setText(Color text) {
        this.text = text;
    }

    @Override
    public Color getFieldBackground() {
        return fieldBackground;
    }

    public void setFieldBackground(Color fieldBackground) {
        this.fieldBackground = fieldBackground;
    }

    @Override
    public Color getFieldHoverBackground() {
        return fieldHoverBackground;
    }

    public void setFieldHoverBackground(Color fieldHoverBackground) {
        this.fieldHoverBackground = fieldHoverBackground;
    }

    @Override
    public Color getFieldBorder() {
        return fieldBorder;
    }

    public void setFieldBorder(Color fieldBorder) {
        this.fieldBorder = fieldBorder;
    }

    @Override
    public Color getPlaceholderText() {
        return placeholderText;
    }

    public void setPlaceholderText(Color placeholderText) {
        this.placeholderText = placeholderText;
    }

    @Override
    public Color getCaret() {
        return caret;
    }

    public void setCaret(Color caret) {
        this.caret = caret;
    }

    @Override
    public Color getSelection() {
        return selection;
    }

    public void setSelection(Color selection) {
        this.selection = selection;
    }

    @Override
    public Color getButtonBackground() {
        return buttonBackground;
    }

    public void setButtonBackground(Color buttonBackground) {
        this.buttonBackground = buttonBackground;
    }

    @Override
    public Color getButtonHoverBackground() {
        return buttonHoverBackground;
    }

    public void setButtonHoverBackground(Color buttonHoverBackground) {
        this.buttonHoverBackground = buttonHoverBackground;
    }

    @Override
    public Color getButtonBorder() {
        return buttonBorder;
    }

    public void setButtonBorder(Color buttonBorder) {
        this.buttonBorder = buttonBorder;
    }

    @Override
    public Color getContainerBackground() {
        return containerBackground;
    }

    public void setContainerBackground(Color containerBackground) {
        this.containerBackground = containerBackground;
    }

    @Override
    public Color getContainerBorder() {
        return containerBorder;
    }

    public void setContainerBorder(Color containerBorder) {
        this.containerBorder = containerBorder;
    }

    @Override
    public Color getScrollThumb() {
        return scrollThumb;
    }

    public void setScrollThumb(Color scrollThumb) {
        this.scrollThumb = scrollThumb;
    }

    @Override
    public Color getScrollTrack() {
        return scrollTrack;
    }

    public void setScrollTrack(Color scrollTrack) {
        this.scrollTrack = scrollTrack;
    }

    @Override
    public Paint getWindowBackground() {
        return windowBackground;
    }

    public void setWindowBackground(Paint windowBackground) {
        this.windowBackground = windowBackground;
    }

    @Override
    public float getCornerRadius() {
        return cornerRadius;
    }

    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius = cornerRadius;
    }

    @Override
    public float getBorderThickness() {
        return borderThickness;
    }

    public void setBorderThickness(float borderThickness) {
        this.borderThickness = borderThickness;
    }

    @Override
    public Color getTooltipBackground() {
        return tooltipBackground;
    }

    public void setTooltipBackground(Color tooltipBackground) {
        this.tooltipBackground = tooltipBackground;
    }

    @Override
    public Color getTooltipBorder() {
        return tooltipBorder;
    }

    public void setTooltipBorder(Color tooltipBorder) {
        this.tooltipBorder = tooltipBorder;
    }

    @Override
    public Color getTooltipText() {
        return tooltipText;
    }

    public void setTooltipText(Color tooltipText) {
        this.tooltipText = tooltipText;
    }
}
