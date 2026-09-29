package me.piitex.engine.ui.text;

import me.piitex.engine.ui.color.Color;

/**
 * A copy of text drawn in {@code color} under it, moved by ({@code offsetX}, {@code offsetY}) points, so the text
 * stands out from a busy background. Pass it to
 * {@link me.piitex.engine.gl.renderer.Renderer#drawText(String, float, float, org.jetbrains.skia.Font,
 * me.piitex.engine.ui.color.Paint, TextAlign, TextShadow) Renderer.drawText}. The shadow keeps its own color, so to fade
 * text and shadow together, draw them inside {@link me.piitex.engine.gl.renderer.Renderer#pushAlpha}.
 */
public record TextShadow(Color color, float offsetX, float offsetY) {

    /**
     * A shadow moved {@code offset} points right and down.
     */
    public TextShadow(Color color, float offset) {
        this(color, offset, offset);
    }
}
