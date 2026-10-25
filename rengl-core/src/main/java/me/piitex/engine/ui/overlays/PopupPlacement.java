package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.PopupHost;

/**
 * Where a floating panel of a given size goes relative to the Element that opened it.
 */
final class PopupPlacement {
    private PopupPlacement() {
    }

    /**
     * Just below {@code anchor}, left edges aligned; above it instead if there is no room below but there is
     * above; pulled back inside the host's area (the window, or the screen's container). Returns {x, y} in the host's
     * coordinates.
     */
    static float[] below(Element anchor, PopupHost host, float width, float height) {
        double[] area = host.getPopupArea();
        float left = (float) area[0], areaTop = (float) area[1], right = left + (float) area[2], bottom = areaTop + (float) area[3];
        float x = (float) anchor.getAbsoluteX();
        float top = (float) anchor.getAbsoluteY();
        float y = top + (float) anchor.getHeight();
        if (y + height > bottom && top - height >= areaTop) {
            y = top - height;
        }
        x = Math.max(left, Math.min(x, right - width));
        y = Math.max(areaTop, Math.min(y, bottom - height));
        return new float[]{x, y};
    }
}
