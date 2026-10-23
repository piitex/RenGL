package me.piitex.engine.ui.overlays;

import me.piitex.engine.Window;
import me.piitex.engine.ui.Element;

/**
 * Where a floating panel of a given size goes relative to the Element that opened it.
 */
final class PopupPlacement {
    private PopupPlacement() {
    }

    /**
     * Just below {@code anchor}, left edges aligned; above it instead if there is no room below but there is
     * above; pulled back inside the window horizontally. Returns {x, y} in window coordinates.
     */
    static float[] below(Element anchor, Window window, float width, float height) {
        float winW = window.getWindowOptions().getWidth();
        float winH = window.getWindowOptions().getHeight();
        float x = (float) anchor.getAbsoluteX();
        float top = (float) anchor.getAbsoluteY();
        float y = top + (float) anchor.getHeight();
        if (y + height > winH && top - height >= 0) {
            y = top - height;
        }
        x = Math.max(0f, Math.min(x, winW - width));
        y = Math.max(0f, Math.min(y, winH - height));
        return new float[]{x, y};
    }
}
