package me.piitex.engine.ui.animation;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;

/**
 * Keeps the diagonal stripes of a striped {@link ProgressBarOverlay} moving, the "barber pole" effect. It only
 * advances the bar's stripe offset; the bar draws the stripes itself, inside its fill, so they follow the fill's
 * width and rounded ends. Reached through {@link ProgressBarOverlay#setStripes}, and never finishes on its own:
 * {@link ProgressBarOverlay#clearStripes()} stops it.
 */
public class StripeTransition implements Transition {
    private boolean finished;

    @Override
    public boolean stop() {
        if (finished) return false;
        finished = true;
        return true;
    }

    @Override
    public void update(Element element, float delta) {
        if (!finished && element instanceof ProgressBarOverlay bar) {
            bar.advanceStripes(delta);
        }
    }

    @Override
    public void render(Renderer renderer, Element element) {
        // Nothing to draw here: the bar paints its own stripes so they stay clipped to its fill.
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
