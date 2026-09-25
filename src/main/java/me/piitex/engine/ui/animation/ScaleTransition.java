package me.piitex.engine.ui.animation;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;

/**
 * Scales an Element's drawing from one size to another over a fixed duration, shaped by an {@link Easing}. It drives
 * {@link Element#setScale}, so it scales around the Element's {@link Element#setScaleOrigin origin}, its center unless
 * set. It plays alongside a fade: {@code element.playTransition(new ScaleTransition(...))} then {@code element.fadeOut(...)}.
 */
public class ScaleTransition implements Transition {
    private final float from;
    private final float to;
    private final float durationSeconds;
    private final Easing easing;
    private final Runnable onComplete;

    private float elapsed = 0f;
    private boolean finished = false;

    /**
     * @param easing     null means {@link Easing#LINEAR}
     * @param onComplete fired once, on the frame this transition finishes. Optional; pass null for none.
     */
    public ScaleTransition(float from, float to, float durationSeconds, Easing easing, Runnable onComplete) {
        this.from = from;
        this.to = to;
        this.durationSeconds = Math.max(0.0001f, durationSeconds);
        this.easing = easing == null ? Easing.LINEAR : easing;
        this.onComplete = onComplete;
    }

    @Override
    public void update(Element target, float delta) {
        if (finished) return;

        elapsed += delta;
        if (elapsed >= durationSeconds) {
            elapsed = durationSeconds;
            finished = true;
        }

        target.setScale(from + (to - from) * easing.apply(elapsed / durationSeconds));

        if (finished && onComplete != null) onComplete.run();
    }

    @Override
    public void render(Renderer renderer, Element target) {
        // Nothing to draw, update() already scaled the target.
    }

    @Override
    public boolean stop() {
        if (finished) return false;
        finished = true;
        return true;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
