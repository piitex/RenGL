package me.piitex.engine.ui.theme;

import me.piitex.engine.ui.color.Color;

/**
 * A black and red theme. Near-black surfaces with a faint warm tint, crimson accents for
 * buttons, selection, sliders and progress fills, and slightly tighter corners than the
 * default for a sharper look.
 */
public class CrimsonTheme extends BaseTheme {
    public CrimsonTheme() {
        setText(new Color(0.95f, 0.92f, 0.92f, 1f));
        setFieldBackground(new Color(0.11f, 0.06f, 0.07f, 1f));
        setFieldHoverBackground(new Color(0.16f, 0.08f, 0.09f, 1f));
        setFieldBorder(new Color(0.45f, 0.10f, 0.14f, 1f));
        setPlaceholderText(new Color(0.55f, 0.40f, 0.42f, 1f));
        setCaret(new Color(1f, 0.25f, 0.30f, 1f));
        setSelection(new Color(0.86f, 0.08f, 0.24f, 0.55f));
        setButtonBackground(new Color(0.62f, 0.07f, 0.13f, 1f));
        setButtonHoverBackground(new Color(0.80f, 0.10f, 0.18f, 1f));
        setButtonBorder(new Color(0.95f, 0.22f, 0.28f, 1f));
        setContainerBackground(new Color(0.07f, 0.07f, 0.07f, 1f));
        setContainerBorder(new Color(0.38f, 0.08f, 0.12f, 1f));
        setWindowBackground(new Color(0.03f, 0.03f, 0.03f, 1f));
        setScrollThumb(new Color(0.62f, 0.07f, 0.13f, 1f));
        setScrollTrack(new Color(0.24f, 0.08f, 0.10f, 1f));
        setTooltipBackground(new Color(0.16f, 0.05f, 0.07f, 0.97f));
        setTooltipBorder(new Color(0.86f, 0.08f, 0.24f, 1f));
        setTooltipText(new Color(0.95f, 0.92f, 0.92f, 1f));
        setCornerRadius(4f);
    }
}
