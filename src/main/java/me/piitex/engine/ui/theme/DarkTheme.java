package me.piitex.engine.ui.theme;

import me.piitex.engine.ui.color.Color;

/**
 * The engine's built-in dark theme: a {@link BaseTheme} with every token overridden to
 * a dark-mode value in the constructor. A custom theme follows the same shape, extending
 * {@link BaseTheme} and setting only the tokens that should differ from the light
 * defaults.
 */
public class DarkTheme extends BaseTheme {
    public DarkTheme() {
        setText(Color.WHITE);
        setFieldBackground(new Color(0.15f, 0.15f, 0.16f, 1f));
        setFieldHoverBackground(new Color(0.20f, 0.20f, 0.21f, 1f));
        setFieldBorder(new Color(0.35f, 0.35f, 0.38f, 1f));
        setPlaceholderText(new Color(0.55f, 0.55f, 0.58f, 1f));
        setCaret(Color.WHITE);
        setSelection(new Color(0.30f, 0.50f, 0.90f, 0.55f));
        setButtonBackground(new Color(0.25f, 0.25f, 0.27f, 1f));
        setButtonHoverBackground(new Color(0.32f, 0.32f, 0.35f, 1f));
        setButtonBorder(new Color(0.45f, 0.45f, 0.48f, 1f));
        setContainerBackground(new Color(0.14f, 0.14f, 0.15f, 1f));
        setContainerBorder(new Color(0.30f, 0.30f, 0.32f, 1f));
        setWindowBackground(new Color(0.08f, 0.08f, 0.09f, 1f));
        setScrollThumb(new Color(0.8f, 0.8f, 0.8f, 1f));
        setScrollTrack(new Color(0.5f, 0.5f, 0.5f, 1f));
        setTooltipBackground(new Color(0.30f, 0.30f, 0.33f, 0.97f));
        setTooltipBorder(new Color(0.50f, 0.50f, 0.54f, 1f));
        setTooltipText(Color.WHITE);
    }
}
