package me.piitex.engine.ui.color;

/**
 * Marker for anything the engine can fill with: a solid {@link Color} (including the
 * self-cycling {@link RainbowColor}), a {@link LinearGradient} (including the scrolling
 * {@link ScrollingGradient}), or a custom {@link AnimatedPaint}. Setters that take a
 * Paint accept any of these; setters that take a {@link Color} accept solid colors
 * only, which still includes {@link RainbowColor}.
 */
public interface Paint {
}