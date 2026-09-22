package me.piitex.engine.ui.animation;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;

/**
 * Fades an Element's overall {@link Element#getOpacity() opacity} between fully
 * transparent and fully opaque over a fixed duration. This is the fade in and fade out
 * every other UI and animation toolkit means by the phrase: the Element's own content,
 * including its background, border, text, image, and children, becomes transparent or
 * opaque together, composited as one unit through
 * {@link Element#renderWithTransitions}, rather than having a colored shape drawn on
 * top of it.
 * <p>
 * An optional {@code color} crossfades with that color instead of with plain
 * transparency. {@link Direction#IN} then starts as solid {@code color} and dissolves
 * into the Element, and {@link Direction#OUT} dissolves from the Element into solid
 * {@code color} rather than revealing whatever is behind it. The color and the
 * Element's own opacity are complementary and drawn together each frame (see
 * {@link #renderBackground}), so the result is one smooth dissolve rather than the
 * Element appearing on top of a flat color overlay.
 * <p>
 * Usually reached through {@link Element#fadeIn}/{@link Element#fadeOut} rather than
 * constructed directly. Attach it with {@link Element#playTransition(Transition)}, or
 * use those convenience methods, which do that for you; either way it removes itself
 * once finished, leaving the Element at the opacity it ended on (1 after fading in, 0
 * after fading out).
 */
public class FadeTransition implements Transition {

    public enum Direction {
        /**
         * Ramps opacity from 0 up to 1 (and, with a color, dissolves from it into the Element).
         */
        IN,
        /**
         * Ramps opacity from 1 down to 0 (and, with a color, dissolves from the Element into it).
         */
        OUT
    }

    private final float durationSeconds;
    private final Direction direction;
    private final Color color; // nullable; null means a plain transparency fade
    private final Runnable onComplete;

    private float elapsed = 0f;
    private boolean finished = false;

    public FadeTransition(float durationSeconds, Direction direction) {
        this(durationSeconds, direction, null, null);
    }

    /**
     * @param onComplete fired once, on the frame this transition finishes. Optional; pass null for none.
     */
    public FadeTransition(float durationSeconds, Direction direction, Runnable onComplete) {
        this(durationSeconds, direction, null, onComplete);
    }

    /**
     * @param color optional. Crossfades with this color instead of plain transparency; pass null for plain transparency. See the class docs.
     */
    public FadeTransition(float durationSeconds, Direction direction, Color color) {
        this(durationSeconds, direction, color, null);
    }

    /**
     * @param color      optional. Crossfades with this color instead of plain transparency; pass null for plain transparency. See the class docs.
     * @param onComplete fired once, on the frame this transition finishes. Optional; pass null for none.
     */
    public FadeTransition(float durationSeconds, Direction direction, Color color, Runnable onComplete) {
        this.durationSeconds = Math.max(0.0001f, durationSeconds); // avoid a divide-by-zero snap for a 0-length fade
        this.direction = direction == null ? Direction.IN : direction;
        this.color = color;
        this.onComplete = onComplete;
    }

    /**
     * How far through the fade this is, from 0 (just started) to 1 (finished), regardless of {@link Direction}.
     */
    public float getProgress() {
        return elapsed / durationSeconds;
    }

    public Direction getDirection() {
        return direction;
    }

    /**
     * The color this crossfades with, or null for a plain transparency fade.
     */
    public Color getColor() {
        return color;
    }

    @Override
    public void update(Element target, float delta) {
        if (finished) return; // defensive; Element stops calling update() once isFinished() is true

        elapsed += delta;
        if (elapsed >= durationSeconds) {
            elapsed = durationSeconds;
            finished = true;
        }

        target.setOpacity(elementFactor());

        if (finished && onComplete != null) {
            onComplete.run();
        }
    }

    /**
     * The Element's own share of visibility right now: rises 0->1 for IN, falls 1->0 for OUT.
     */
    private float elementFactor() {
        float progress = getProgress();
        return direction == Direction.IN ? progress : (1f - progress);
    }

    @Override
    public void renderBackground(Renderer renderer, Element target) {
        if (color == null) return;

        // Complementary to the Element's own opacity, so the two dissolve into each
        // other rather than the Element simply popping in on top of a flat color.
        float colorFactor = 1f - elementFactor();
        float alpha = color.getA() * colorFactor;
        if (alpha <= 0f) return;

        Color faded = new Color(color.getR(), color.getG(), color.getB(), alpha);
        renderer.drawQuad(
                (float) target.getX(), (float) target.getY(), (float) target.getWidth(), (float) target.getHeight(),
                faded, target.getStyling().getCornerRadius()
        );
    }

    @Override
    public void render(Renderer renderer, Element target) {
        // No-op; see renderBackground for the optional color crossfade.
    }

    /**
     * Stops the fade where it is, leaving the Element at its current opacity, without firing {@code onComplete}.
     */
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
