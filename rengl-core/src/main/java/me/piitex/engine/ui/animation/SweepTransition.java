package me.piitex.engine.ui.animation;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;

/**
 * The looping animation for a {@link ProgressBarOverlay}: a segment of the fill color bounces back and forth across the
 * track, reversing direction at each end, until {@link #stop()} is called. Reached through {@link
 * ProgressBarOverlay#setIndeterminate}. Unlike most transitions it never finishes on
 * its own, and it draws its own segment in {@link #render} rather than changing the
 * bar's progress. That is the same seam a custom bar animation such as a shimmer or a
 * pulse would use: implement {@link Transition}, draw from {@code render}, and start it
 * with {@code bar.playTransition(...)}.
 */
public class SweepTransition implements Transition {
    private static final float SEGMENT_FRACTION = 0.35f;

    private final float periodSeconds;
    private float elapsed;
    private boolean finished;

    /**
     * @param periodSeconds how long one full there-and-back cycle takes
     */
    public SweepTransition(float periodSeconds) {
        this.periodSeconds = Math.max(0.05f, periodSeconds);
    }

    @Override
    public boolean stop() {
        if (finished) return false;
        finished = true;
        return true;
    }

    @Override
    public void update(Element element, float delta) {
        elapsed = (elapsed + delta) % periodSeconds;
    }

    @Override
    public void render(Renderer renderer, Element element) {
        if (finished || !(element instanceof ProgressBarOverlay bar) || bar.getOpacity() <= 0f) return;

        float x = (float) bar.getX();
        float w = (float) bar.getWidth();
        float h = (float) bar.getHeight();
        float segment = w * SEGMENT_FRACTION;

        // Bounces: one period is a full there-and-back, easing to a stop at each end
        // before reversing, so the segment never leaves the track.
        float phase = elapsed / periodSeconds;                    // 0..1
        float t = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;  // 0 -> 1 -> 0
        float left = x + (w - segment) * Easing.EASE_IN_OUT.apply(t);

        float radius = Math.min(bar.getStyling().getCornerRadius(), Math.min(h / 2f, segment / 2f));
        renderer.drawQuad(left, (float) bar.getY(), segment, h, bar.getFillColor(), radius);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
