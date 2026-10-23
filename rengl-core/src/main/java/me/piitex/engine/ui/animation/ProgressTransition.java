package me.piitex.engine.ui.animation;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;

/**
 * Tweens a {@link ProgressBarOverlay}'s progress from wherever it currently is to a
 * target value over a fixed duration, shaped by an {@link Easing}. Usually reached
 * through {@link ProgressBarOverlay#animateTo} rather than constructed directly. The
 * start value is read on the first frame it runs, not at construction, so it's safe
 * to queue right after another tween. Finishes itself, leaving the bar at the target.
 */
public class ProgressTransition extends LockableTransition<ProgressTransition> {
    private final double target;
    private final float durationSeconds;
    private final Easing easing;
    private final Runnable onComplete;

    private double start;
    private boolean started;
    private float elapsed;
    private boolean finished;

    /**
     * @param target     progress to end at, clamped to [0, 1]
     * @param easing     null means {@link Easing#LINEAR}
     * @param onComplete fired once when the tween reaches its target (not when cancelled); may be null
     */
    public ProgressTransition(double target, float durationSeconds, Easing easing, Runnable onComplete) {
        this.target = Math.max(0, Math.min(1, target));
        this.durationSeconds = Math.max(0.0001f, durationSeconds);
        this.easing = easing == null ? Easing.LINEAR : easing;
        this.onComplete = onComplete;
    }

    /**
     * Stops this tween where it is, without firing {@code onComplete}.
     */
    @Override
    public boolean stop() {
        if (finished) return false;
        finished = true;
        return true;
    }

    /**
     * Same as {@link #stop()}.
     */
    public void cancel() {
        stop();
    }

    @Override
    public void update(Element element, float delta) {
        if (finished) return;
        if (!(element instanceof ProgressBarOverlay bar)) {
            finished = true;
            return;
        }
        if (!started) {
            start = bar.getProgress();
            started = true;
        }

        elapsed += delta;
        float t = Math.min(1f, elapsed / durationSeconds);
        bar.applyAnimatedProgress(start + (target - start) * easing.apply(t));

        if (t >= 1f) {
            bar.applyAnimatedProgress(target); // land exactly, whatever the easing curve returned
            finished = true;
            if (onComplete != null) onComplete.run();
        }
    }

    @Override
    public void render(Renderer renderer, Element element) {
        // Nothing to draw: the bar draws its own fill from the progress this updates.
    }

    @Override
    protected void jumpToEnd() {
        elapsed = durationSeconds;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
