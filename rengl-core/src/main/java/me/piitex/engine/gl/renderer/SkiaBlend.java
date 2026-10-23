package me.piitex.engine.gl.renderer;

import me.piitex.engine.graphics.BlendMode;

/**
 * Package-private: the Skia blend mode for each of the engine's.
 */
final class SkiaBlend {
    private SkiaBlend() {
    }

    static org.jetbrains.skia.BlendMode of(BlendMode mode) {
        return switch (mode == null ? BlendMode.NORMAL : mode) {
            case NORMAL -> org.jetbrains.skia.BlendMode.SRC_OVER;
            case ADD -> org.jetbrains.skia.BlendMode.PLUS;
            case MULTIPLY -> org.jetbrains.skia.BlendMode.MULTIPLY;
            case SCREEN -> org.jetbrains.skia.BlendMode.SCREEN;
            case OVERLAY -> org.jetbrains.skia.BlendMode.OVERLAY;
            case DARKEN -> org.jetbrains.skia.BlendMode.DARKEN;
            case LIGHTEN -> org.jetbrains.skia.BlendMode.LIGHTEN;
            case COLOR_DODGE -> org.jetbrains.skia.BlendMode.COLOR_DODGE;
            case COLOR_BURN -> org.jetbrains.skia.BlendMode.COLOR_BURN;
            case HARD_LIGHT -> org.jetbrains.skia.BlendMode.HARD_LIGHT;
            case SOFT_LIGHT -> org.jetbrains.skia.BlendMode.SOFT_LIGHT;
            case DIFFERENCE -> org.jetbrains.skia.BlendMode.DIFFERENCE;
            case EXCLUSION -> org.jetbrains.skia.BlendMode.EXCLUSION;
        };
    }
}
