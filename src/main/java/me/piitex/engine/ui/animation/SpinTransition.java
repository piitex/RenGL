package me.piitex.engine.ui.animation;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.LoadingSpinnerOverlay;

/**
 * The looping animation behind a {@link LoadingSpinnerOverlay}: a ring of dots with a
 * bright "head" that chases around the ring, each dot fading and shrinking behind it
 * into a comet-like trail. It never finishes on its own; {@link #stop()} ends it. Like
 * {@link SweepTransition}, it draws from {@link #render}, so a different look, such as
 * an arc, bars, or a pulsing dot, is another {@link Transition} started with
 * {@code playTransition}.
 */
public class SpinTransition implements Transition {
    private static final int DOTS = 12;

    private final float periodSeconds;
    private float elapsed;
    private boolean finished;

    /** @param periodSeconds how long one full revolution takes */
    public SpinTransition(float periodSeconds) {
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
        if (finished || !(element instanceof LoadingSpinnerOverlay spinner) || spinner.getOpacity() <= 0f) return;

        float size = (float) Math.min(spinner.getWidth(), spinner.getHeight());
        float cx = (float) (spinner.getX() + spinner.getWidth() / 2);
        float cy = (float) (spinner.getY() + spinner.getHeight() / 2);
        float dotMax = size * 0.09f;
        float ring = size / 2f - dotMax;
        Color base = spinner.getColor();

        // Head position in dot units, advancing continuously so motion is smooth
        // rather than stepping dot to dot.
        float head = elapsed / periodSeconds * DOTS;
        for (int i = 0; i < DOTS; i++) {
            float behind = ((head - i) % DOTS + DOTS) % DOTS / DOTS; // 0 at the head -> ~1 just ahead of it
            float strength = 1f - behind;
            strength *= strength;
            double angle = -Math.PI / 2 + i * 2 * Math.PI / DOTS;
            Color c = new Color(base.getR(), base.getG(), base.getB(), base.getA() * (0.15f + 0.85f * strength));
            renderer.drawCircle(cx + (float) Math.cos(angle) * ring, cy + (float) Math.sin(angle) * ring,
                    dotMax * (0.45f + 0.55f * strength), c);
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}
