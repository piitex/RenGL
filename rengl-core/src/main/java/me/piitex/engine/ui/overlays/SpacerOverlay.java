package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;

/**
 * Empty space in a {@link me.piitex.engine.ui.layout.HorizontalLayout} or {@link me.piitex.engine.ui.layout.VerticalLayout}:
 * draws nothing and takes no input. Made with no size it stretches, taking whatever room the other children leave (see
 * {@link #setLayoutWeight}), which pushes the ones after it to the far end:
 * <pre>{@code
 * row.addElement(title);
 * row.addElement(new SpacerOverlay());   // everything after this sits at the right edge
 * row.addElement(closeButton);
 * }</pre>
 * Made with a size it is a fixed gap.
 */
public class SpacerOverlay extends Overlay {
    /**
     * A flexible spacer, sharing the leftover room of its layout equally with any other of weight 1.
     */
    public SpacerOverlay() {
        setWidth(0);
        setHeight(0);
        setLayoutWeight(1);
    }

    /**
     * A fixed gap: {@code size} points along a row, and along a column, whichever the layout it is in runs.
     */
    public SpacerOverlay(double size) {
        setWidth(size);
        setHeight(size);
    }

    @Override
    public void render(Renderer renderer) {
    }
}
